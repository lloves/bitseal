package org.bitseal.services;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Iterator;

import org.bitseal.controllers.TaskController;
import org.bitseal.core.App;
import org.bitseal.core.QueueRecordProcessor;
import org.bitseal.data.Address;
import org.bitseal.data.Message;
import org.bitseal.data.Payload;
import org.bitseal.data.Pubkey;
import org.bitseal.data.QueueRecord;
import org.bitseal.database.AddressProvider;
import org.bitseal.database.MessageProvider;
import org.bitseal.database.PayloadProvider;
import org.bitseal.database.PubkeyProvider;
import org.bitseal.database.QueueRecordProvider;
import org.bitseal.database.QueueRecordsTable;
import org.bitseal.network.NetworkHelper;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;

/**
 * This class handles all the long-running processing required
 * by the application. 
 * 
 * @author Jonathan Coe
 */
public class BackgroundService extends IntentService
{
	/**
	 * This constant determines whether or not the app will do
	 * proof of work for pubkeys and messages that it creates. 
	 * If not, it will expect servers to do the proof of work. 
	 */
	public static final boolean DO_POW = true;
	
	/**
	 * The 'time to live' value (in seconds) that we use in the 'first attempt' to create
	 * and send some types of objects (such as msgs sent by us). This is done
	 * because in protocol version 3, objects with a lower time to live require less
	 * proof of work for the network to relay them. <br><br>
	 * 
	 * Therefore in some situations it is advantageous to use a low time to live
	 * when creating and sending an object, for example when you are sending a
	 * msg and the recipient is online and therefore able to receive and acknowledge
	 * it immediately. 
	 */
	public static final long FIRST_ATTEMPT_TTL = 3600; // Currently set to 1 hour
	
	/**
	 * The 'time to live' value (in seconds) that we use in all attempts after the
	 * first attempt  to create and send some types of objects (such as msgs sent
	 * by us). <br><br>
	 * 
	 * If we create and send out an object using a low time to live and the first attempt is 
	 * not successful (e.g. we do not receive an acknowledgment for a sent msg) then we can 
	 * re-create and re-send the object with a longer time to live. That time to live is 
	 * determined by this constant.  
	 */
	public static final long SUBSEQUENT_ATTEMPTS_TTL = 86400; // Currently set to 1 day
		
	/**
	 * This 'maximum attempts' constant determines the number of times
	 * that a task will be attempted before it is abandoned and deleted
	 * from the queue.
	 */
	public static final int MAXIMUM_ATTEMPTS = 500;
	
    /**
     * The normal amount of time in seconds between each attempt to start the
     * BackgroundService, in seconds. e.g. If this value is set to 60, then
     * a PendingIntent will be registered with the AlarmManager to start the
     * background service every minute. 
     */
	public static final int BACKGROUND_SERVICE_NORMAL_START_INTERVAL = 60;
		
	/** Determines how often the database cleaning routine should be run, in seconds. */
	private static final long TIME_BETWEEN_DATABASE_CLEANING = 3600;
		
	// Constants to identify requests from the UI
	public static final String UI_REQUEST = "uiRequest";
	public static final String UI_REQUEST_SEND_MESSAGE = "sendMessage";
	public static final String UI_REQUEST_CREATE_IDENTITY = "createIdentity";
	
	// Used when broadcasting Intents to the UI so that it can refresh the data it is displaying
	public static final String UI_NOTIFICATION = "uiNotification";
	
	// Constants that identify request for periodic background processing
	public static final String PERIODIC_BACKGROUND_PROCESSING_REQUEST = "periodicBackgroundProcessingReqest";
	public static final String BACKGROUND_PROCESSING_REQUEST = "doBackgroundProcessing";
	
	// Constants to identify data sent to this Service from the UI
	public static final String MESSAGE_ID = "messageId";
	public static final String ADDRESS_ID = "addressId";
	
	// The tasks for performing the first major function of the application: creating a new identity
	public static final String TASK_CREATE_IDENTITY = "createIdentity";
	public static final String TASK_DISSEMINATE_PUBKEY = "disseminatePubkey";
	
	// The tasks for performing the second major function of the application: sending messages
	public static final String TASK_SEND_MESSAGE = "sendMessage";
	public static final String TASK_PROCESS_OUTGOING_MESSAGE = "processOutgoingMessage";
	public static final String TASK_DISSEMINATE_MESSAGE = "disseminateMessage";
			
	private static final String TAG = "BACKGROUND_SERVICE";
	
	public BackgroundService() 
	{
		super("BackgroundService");
	}
	
	/**
	 * Handles requests sent to the BackgroundService via Intents
	 * 
	 * @param - An Intent object that has been received by the 
	 * BackgroundService
	 */
	@Override
	protected void onHandleIntent(Intent i)
	{
		Log.i(TAG, "BackgroundService.onHandleIntent() called");
		
		// Determine whether the intent came from a request for periodic
		// background processing or from a UI request
		if (i.hasExtra(PERIODIC_BACKGROUND_PROCESSING_REQUEST))
		{
			processTasks();
		}
		
		else if (i.hasExtra(UI_REQUEST))
		{
			String uiRequest = i.getStringExtra(UI_REQUEST);
			
			TaskController taskController = new TaskController();
			
			if (uiRequest.equals(UI_REQUEST_SEND_MESSAGE))
			{
				Log.i(TAG, "Responding to UI request to run the 'send message' task");
				
				// Get the ID of the Message object from the intent
				Bundle extras = i.getExtras();
				long messageID = extras.getLong(MESSAGE_ID);
				
				// Attempt to retrieve the Message from the database. If it has been deleted by the user
				// then we should abort the sending process. 
				Message messageToSend = null;
				try
				{
					MessageProvider msgProv = MessageProvider.get(getApplicationContext());
					messageToSend = msgProv.searchForSingleRecord(messageID);
				}
				catch (RuntimeException e)
				{
					Log.i(TAG, "While running BackgroundService.onHandleIntent() and attempting to process a UI request of type\n"
							+ UI_REQUEST_SEND_MESSAGE + ", the attempt to retrieve the Message object from the database failed.\n"
							+ "The message sending process will therefore be aborted.");
					return;
				}
								
				// Create a new QueueRecord for the 'send message' task and save it to the database
				QueueRecordProcessor queueProc = new QueueRecordProcessor();
				QueueRecord queueRecord = queueProc.createAndSaveQueueRecord(TASK_SEND_MESSAGE, 0, 0, messageToSend, null);
				
				// Also create a new QueueRecord for re-sending this msg in the event that we do not receive an acknowledgment for it
				// before its time to live expires. If we do receive the acknowledgment before then, this QueueRecord will be deleted
				queueProc.createAndSaveQueueRecord(TASK_SEND_MESSAGE, (System.currentTimeMillis() / 1000) + FIRST_ATTEMPT_TTL, 1, messageToSend, null);
				
				// First check whether an Internet connection is available. If not, the QueueRecord which records the 
				// need to send the message will be stored (as above) and processed later
				if (NetworkHelper.checkInternetAvailability() == true)
				{
					// Attempt to send the message
					taskController.sendMessage(queueRecord, messageToSend, DO_POW, FIRST_ATTEMPT_TTL, FIRST_ATTEMPT_TTL);
				}
			}
			
			else if (uiRequest.equals(UI_REQUEST_CREATE_IDENTITY))
			{
				Log.i(TAG, "Responding to UI request to run the 'create new identity' task");
				
				// Get the ID of the Address object from the intent
				Bundle extras = i.getExtras();
				long addressId = extras.getLong(ADDRESS_ID);
				
				// Attempt to retrieve the Address from the database. If it has been deleted by the user
				// then we should abort the sending process. 
				Address address = null;
				try
				{
					AddressProvider addProv = AddressProvider.get(getApplicationContext());
					address = addProv.searchForSingleRecord(addressId);
				}
				catch (RuntimeException e)
				{
					Log.i(TAG, "While running BackgroundService.onHandleIntent() and attempting to process a UI request of type\n"
							+ UI_REQUEST_CREATE_IDENTITY + ", the attempt to retrieve the Address object from the database failed.\n"
							+ "The identity creation process will therefore be aborted.");
					return;
				}
				
				// Create a new QueueRecord for the create identity task and save it to the database
				QueueRecordProcessor queueProc = new QueueRecordProcessor();
				QueueRecord queueRecord = queueProc.createAndSaveQueueRecord(TASK_CREATE_IDENTITY, 0, 0, address, null);
				
				// Attempt to complete the create identity task
				taskController.createIdentity(queueRecord, DO_POW);
			}
		}
		else
		{
			Log.e(TAG, "BackgroundService.onHandleIntent() was called without a valid extra to specify what the service should do.");
		}
		
		// Create a new intent that will be used to run processTasks() again after a period of time
		Intent intent = new Intent(getApplicationContext(), BackgroundService.class);
		intent.putExtra(BackgroundService.PERIODIC_BACKGROUND_PROCESSING_REQUEST, BackgroundService.BACKGROUND_PROCESSING_REQUEST);
		PendingIntent pendingIntent = PendingIntent.getService(getApplicationContext(), 0, intent, PendingIntent.FLAG_CANCEL_CURRENT);
		
	    // Get the current time and add the number of seconds specified by BACKGROUND_SERVICE_START_INTERVAL_SECONDS to it
	    Calendar cal = Calendar.getInstance();
    	cal.add(Calendar.SECOND, BACKGROUND_SERVICE_NORMAL_START_INTERVAL);
    	Log.i(TAG, "The BackgroundService will be restarted in " + BACKGROUND_SERVICE_NORMAL_START_INTERVAL + " seconds");
	    
	    // Register the pending intent with AlarmManager
	    AlarmManager am = (AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE);
	    am.set(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pendingIntent);
	}
	
	/**
	 * Runs periodic background processing. <br><br>
	 * 
	 * This method will first check whether there are any QueueRecord objects saved
	 * in the database. If there are, it will attempt to complete the task recorded
	 * by each of those QueueRecords in turn. After that, it will run the 'check for
	 * messages' task. If no QueueRecords are found in the database, it will run the
	 * 'check for messages' task. 
	 */
	private void processTasks()
	{
		Log.i(TAG, "BackgroundService.processTasks() called");
		
		TaskController taskController = new TaskController();
		
		// Check the database TaskQueue table for any queued tasks
		QueueRecordProvider queueProv = QueueRecordProvider.get(getApplicationContext());
		QueueRecordProcessor queueProc = new QueueRecordProcessor();
		
		ArrayList<QueueRecord> queueRecords = queueProv.getAllQueueRecords();
		Log.i(TAG, "Number of QueueRecords found: " + queueRecords.size());
		
		// Ignore any QueueRecords that have a 'trigger time' in the future
		long currentTime = System.currentTimeMillis() / 1000;
		Iterator<QueueRecord> iterator = queueRecords.iterator();
		while (iterator.hasNext())
		{
		    QueueRecord q = iterator.next();
			if (q.getTriggerTime() > currentTime)
			{
				Log.i(TAG, "Ignoring a QueueRecord for a " + q.getTask() + " task because its trigger time has not been reached yet.\n"
						+ "Its trigger time will be reached in roughly " + (q.getTriggerTime() - currentTime) + " seconds");
				iterator.remove();;
			}
		}
		
		if (queueRecords.size() > 0)
		{
			// Sort the queue records so that we will process the records with the earliest 'last attempt time' first
			Collections.sort(queueRecords);
			
			// Process each queued task in turn, removing them from the database if completed successfully
			for (QueueRecord q : queueRecords)
			{
				Log.i(TAG, "Found a QueueRecord with the task " + q.getTask() + " and number of attempts " + q.getAttempts());
				
				// First check how many times the task recorded by this QueueRecord has been attempted.
				// If it has been attempted a very high number of times (all without success) then we
				// will delete it.
				int attempts = q.getAttempts();
				String task = q.getTask();
				if (attempts > MAXIMUM_ATTEMPTS)
				{
					if (task.equals(TASK_SEND_MESSAGE))
					{
						MessageProvider msgProv = MessageProvider.get(getApplicationContext());
						Message messageToSend = msgProv.searchForSingleRecord(q.getObject0Id());
						messageToSend.setStatus(Message.STATUS_SENDING_FAILED);
						msgProv.updateMessage(messageToSend);
					}
					queueProc.deleteQueueRecord(q);
					continue;
				}
				
				if (task.equals(TASK_SEND_MESSAGE))
				{
					// Check whether an Internet connection is available. If not, move on to the next QueueRecord
					if (NetworkHelper.checkInternetAvailability() == true)
					{
						// Attempt to retrieve the Message from the database. If it has been deleted by the user
						// then we should abort the sending process. 
						try
						{
							MessageProvider msgProv = MessageProvider.get(getApplicationContext());
							Message messageToSend = msgProv.searchForSingleRecord(q.getObject0Id());
							
							// First we need to check whether there is already an existing QueueRecord for sending this msg 
							// with a lower trigger time than this QueueRecord. If there is, push the trigger time of this QueueRecord
							// further into the future
							ArrayList<QueueRecord> matchingRecords = queueProv.searchQueueRecords(QueueRecordsTable.COLUMN_OBJECT_0_ID, String.valueOf(q.getObject0Id()));
							for (QueueRecord match : matchingRecords)
							{
								if (match.getTask().equals(TASK_SEND_MESSAGE) || match.getTask().equals(TASK_PROCESS_OUTGOING_MESSAGE))
								{
									if (match.getTriggerTime() < q.getTriggerTime())
									{
										if (match.getRecordCount() == 0)
										{
											q.setTriggerTime(q.getTriggerTime() + FIRST_ATTEMPT_TTL);
										}
										else
										{
											q.setTriggerTime(q.getTriggerTime() + SUBSEQUENT_ATTEMPTS_TTL);
										}
										queueProv.updateQueueRecord(q);
										continue;
									}
								}
							}
							
							// Otherwise, attempt to complete the send message task
							if (q.getRecordCount() == 0)
							{
								taskController.sendMessage(q, messageToSend, DO_POW, FIRST_ATTEMPT_TTL, FIRST_ATTEMPT_TTL);
							}
							else
							{
								// Create a new QueueRecord for re-sending this msg in the event that we do not receive an acknowledgment for it
								// before its time to live expires. If we do receive the acknowledgment before then, this QueueRecord will be deleted
								queueProc.createAndSaveQueueRecord(TASK_SEND_MESSAGE, (System.currentTimeMillis() / 1000) + SUBSEQUENT_ATTEMPTS_TTL, q.getRecordCount() + 1, messageToSend, null);
								
								taskController.sendMessage(q, messageToSend, DO_POW, SUBSEQUENT_ATTEMPTS_TTL, SUBSEQUENT_ATTEMPTS_TTL);
							}							
						}
						catch (RuntimeException e)
						{
							Log.i(TAG, "While running BackgroundService.processTasks() and attempting to process a task of type\n"
									+ TASK_SEND_MESSAGE + ", the attempt to retrieve the Message object from the database failed.\n"
									+ "The message sending process will therefore be aborted.");
							queueProv.deleteQueueRecord(q);
							continue;
						}
					}
				}
				
				else if (task.equals(TASK_PROCESS_OUTGOING_MESSAGE))
				{
					// Attempt to retrieve the Message from the database. If it has been deleted by the user
					// then we should abort the sending process. 
					Message messageToSend = null;
					try
					{
						MessageProvider msgProv = MessageProvider.get(getApplicationContext());
						messageToSend = msgProv.searchForSingleRecord(q.getObject0Id());
					}
					catch (RuntimeException e)
					{
						Log.i(TAG, "While running BackgroundService.processTasks() and attempting to process a task of type\n"
								+ TASK_PROCESS_OUTGOING_MESSAGE + ", the attempt to retrieve the Message object from the database failed.\n"
								+ "The message sending process will therefore be aborted.");
						queueProv.deleteQueueRecord(q);
						continue;
					}
					 
					// Now retrieve the pubkey for the address we are sending the message to
					PubkeyProvider pubProv = PubkeyProvider.get(App.getContext());
					Pubkey toPubkey = pubProv.searchForSingleRecord(q.getObject1Id());
						 
					// Attempt to process and send the message
					if (q.getRecordCount() == 0)
					{
						taskController.processOutgoingMessage(q, messageToSend, toPubkey, DO_POW, FIRST_ATTEMPT_TTL);
					}
					else
					{
						taskController.processOutgoingMessage(q, messageToSend, toPubkey, DO_POW, SUBSEQUENT_ATTEMPTS_TTL);
					}
				}
				
				else if (task.equals(TASK_DISSEMINATE_MESSAGE))
				{
					// First check whether an Internet connection is available. If not, move on to the next QueueRecord
					if (NetworkHelper.checkInternetAvailability() == true)
					{
						PayloadProvider payProv = PayloadProvider.get(getApplicationContext());
						Payload payloadToSend = payProv.searchForSingleRecord(q.getObject0Id());
						 
						// Now retrieve the pubkey for the address we are sending the message to
						PubkeyProvider pubProv = PubkeyProvider.get(App.getContext());
						Pubkey toPubkey = pubProv.searchForSingleRecord(q.getObject1Id());
							 
						// Attempt to process and send the message
						taskController.disseminateMessage(q, payloadToSend, toPubkey, DO_POW);
					}
				}
				
				else if (task.equals(TASK_CREATE_IDENTITY))
				{
					taskController.createIdentity(q, DO_POW);
				}
				
				else if (task.equals(TASK_DISSEMINATE_PUBKEY))
				{
					// First check whether an Internet connection is available. If not, move on to the next QueueRecord
					if (NetworkHelper.checkInternetAvailability() == true)
					{
						PayloadProvider payProv = PayloadProvider.get(getApplicationContext());
						Payload payloadToSend = payProv.searchForSingleRecord(q.getObject0Id());
						taskController.disseminatePubkey(q, payloadToSend, DO_POW);
					}
				}
				
				else
				{
					Log.e(TAG, "While running BackgroundService.processTasks(), a QueueRecord with an invalid task " +
							"field was found. The invalid task field was : " + task);
				}
			}
			
			runPeriodicTasks();
		}
		else // If there are no other tasks that we need to do
		{
			runPeriodicTasks();
			
			// Check whether it is time to run the 'clean database' routine. If yes then run it. 
			if (checkIfDatabaseCleaningIsRequired())
			{
				Intent intent = new Intent(getBaseContext(), DatabaseCleaningService.class);
			    intent.putExtra(DatabaseCleaningService.EXTRA_RUN_DATABASE_CLEANING_ROUTINE, true);
			    startService(intent);
			}
		}
	}
	
	/**
	 * Runs the tasks that must be done periodically, e.g. checking for new msgs. 
	 */
	private void runPeriodicTasks()
	{
		Log.i(TAG, "BackgroundService.runPeriodicTasks() called");
		
		runCheckForMessagesTask();
		runCheckIfPubkeyReDisseminationIsDueTask();
	}
	
	/**
	 * This method runs the 'check for messages and send acks' task, via
	 * the TaskController. <br><br>
	 * 
	 * Note that we do NOT create QueueRecords for this task, because it
	 * is a default action that will be carried out regularly anyway.
	 */
	private void runCheckForMessagesTask()
	{
		Log.i(TAG, "BackgroundService.runCheckForMessagesTask() called");
		
		// First check whether an Internet connection is available. If not, we cannot proceed. 
		if (NetworkHelper.checkInternetAvailability() == true)
		{
			// Only run this task if we have at least one Address!
			AddressProvider addProv = AddressProvider.get(getApplicationContext());
			ArrayList<Address> myAddresses = addProv.getAllAddresses();
			if (myAddresses.size() > 0)
			{
				// Attempt to complete the task
				TaskController taskController = new TaskController();
				taskController.checkForMessagesAndSendAcks();
			}
			else
			{
				Log.i(TAG, "No Addresses were found in the application database, so we will not run the 'Check for messages' task");
			}
		}
	}
	
	/**
	 * This method runs the 'check if pubkey re-dissemination is due' task, via
	 * the TaskController. <br><br>
	 * 
	 * Note that we do NOT create QueueRecords for this task, because it is a
	 * default action that will be carried out regularly anyway. 
	 */
	private void runCheckIfPubkeyReDisseminationIsDueTask()
	{
		Log.i(TAG, "BackgroundService.runCheckIfPubkeyReDisseminationIsDueTask() called");
		
		// First check whether an Internet connection is available. If not, we cannot proceed. 
		if (NetworkHelper.checkInternetAvailability() == true)
		{		
			// Only run this task if we have at least one Address!
			AddressProvider addProv = AddressProvider.get(getApplicationContext());
			ArrayList<Address> myAddresses = addProv.getAllAddresses();
			if (myAddresses.size() > 0)
			{
				// Attempt to complete the task
				TaskController taskController = new TaskController();
				taskController.checkIfPubkeyDisseminationIsDue(DO_POW);
			}
			else
			{
				Log.i(TAG, "No Addresses were found in the application database, so we will not run the 'Check if pubkey re-dissemination is due' task");
			}
		}
	}
	
	/**
	 * Determines whether it is time to run the 'clean database' routine,
	 * which deletes defunct data. This is based on the period of time since
	 * this routine was last run. 
	 * 
	 * @return A boolean indicating whether or not the 'clean database' routine
	 * should be run.
	 */
	private boolean checkIfDatabaseCleaningIsRequired()
	{
		Log.i(TAG, "BackgroundService.checkIfDatabaseCleaningIsRequired() called");
		
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		long currentTime = System.currentTimeMillis() / 1000;
		long lastDataCleanTime = prefs.getLong(DatabaseCleaningService.LAST_DATABASE_CLEAN_TIME, 0);
		
		if (lastDataCleanTime == 0)
		{
			return true;
		}
		else
		{
			long timeSinceLastDataClean = currentTime - lastDataCleanTime;
			if (timeSinceLastDataClean > TIME_BETWEEN_DATABASE_CLEANING)
			{
				return true;
			}
			else
			{
				long timeTillNextDatabaseClean = TIME_BETWEEN_DATABASE_CLEANING - timeSinceLastDataClean;
				Log.i(TAG, "The database cleaning service was last run " + timeSinceLastDataClean + " seconds ago. It will be run again in " + timeTillNextDatabaseClean + " seconds.");
				return false;
			}
		}
	}
}