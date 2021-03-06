package org.bitseal.crypt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.SignatureException;

import org.bitseal.core.AddressProcessor;
import org.bitseal.data.Pubkey;
import org.bitseal.data.UnencryptedMsg;
import org.bitseal.util.ArrayCopier;
import org.bitseal.util.ByteFormatter;
import org.bitseal.util.ByteUtils;
import org.bitseal.util.VarintEncoder;
import org.spongycastle.jce.interfaces.ECPrivateKey;
import org.spongycastle.jce.interfaces.ECPublicKey;

import android.util.Log;

/**
 * Offers methods for creating and verifying ECDSA signatures. 
 * 
 * @author Jonathan Coe
 */
public class SigProcessor
{	
	private static final String ALGORITHM = "ECDSA";
	private static final String PROVIDER = "SC"; // Spongy Castle
		
	private static final String TAG = "SIG_PROCESSOR";
	
	/**
	 * Constructs the payload necessary to sign or verify the signature of a PubKey
	 * 
	 * @param pubkey - The PubKey object that we wish to sign or verify the signature of
	 * 
	 * @return A byte[] containing the constructed payload. 
	 */
	public byte[] createPubkeySignaturePayload(Pubkey pubkey)
	{	
		byte[] payload = null;
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		try
		{
			outputStream.write(ByteUtils.longToBytes(pubkey.getExpirationTime()));
			outputStream.write(ByteUtils.intToBytes(pubkey.getObjectType()));
			outputStream.write(VarintEncoder.encode(pubkey.getObjectVersion()));
			outputStream.write(VarintEncoder.encode(pubkey.getStreamNumber()));
			
			if (pubkey.getObjectVersion() >= 4) // Pubkeys of version 4 and above have the address tag included in the signature payload
			{
				String addressString = new AddressGenerator().recreateAddressString(pubkey.getObjectVersion(), pubkey.getStreamNumber(), pubkey.getPublicSigningKey(), pubkey.getPublicEncryptionKey());
				outputStream.write(new AddressProcessor().calculateAddressTag(addressString));
			}
			
			outputStream.write(ByteUtils.intToBytes(pubkey.getBehaviourBitfield()));
			
			// If the public signing or public encryption key have their leading 0x04 byte in place then we need to remove them
			byte[] publicSigningKey = pubkey.getPublicSigningKey();
			if (publicSigningKey[0] == (byte) 4  && publicSigningKey.length == 65)
			{
				publicSigningKey = ArrayCopier.copyOfRange(publicSigningKey, 1, publicSigningKey.length);
			}
			outputStream.write(publicSigningKey);
			
			byte[] publicEncryptionKey = pubkey.getPublicEncryptionKey();
			if (publicEncryptionKey[0] == (byte) 4  && publicEncryptionKey.length == 65)
			{
				publicEncryptionKey = ArrayCopier.copyOfRange(publicEncryptionKey, 1, publicEncryptionKey.length);
			}
			outputStream.write(publicEncryptionKey);
			
			outputStream.write(VarintEncoder.encode(pubkey.getNonceTrialsPerByte()));
			outputStream.write(VarintEncoder.encode(pubkey.getExtraBytes()));
			
			payload = outputStream.toByteArray();
		} 
		catch (IOException e)
		{
			throw new RuntimeException("IOException occurred in SigProcessor.createPubkeySignaturePayload()", e);
		}

		return payload;
	}
	
	/**
	 * Constructs the payload necessary to sign or verify the signature of an UnencryptedMsg
	 * 
	 * @param unencMsg - The UnencryptedMsg object that we wish to sign or verify the signature of
	 * 
	 * @return A byte[] containing the constructed payload. 
	 */
	public byte[] createUnencryptedMsgSignaturePayload(UnencryptedMsg unencMsg)
	{	
		byte[] payload = null;
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		try 
		{
			outputStream.write(ByteUtils.longToBytes(unencMsg.getExpirationTime()));
			outputStream.write(ByteUtils.intToBytes(unencMsg.getObjectType()));
			outputStream.write(VarintEncoder.encode(unencMsg.getObjectVersion()));
			outputStream.write(VarintEncoder.encode(unencMsg.getStreamNumber()));
			outputStream.write(VarintEncoder.encode(unencMsg.getSenderAddressVersion()));
			outputStream.write(VarintEncoder.encode(unencMsg.getSenderStreamNumber()));
			outputStream.write(ByteUtils.intToBytes(unencMsg.getBehaviourBitfield()));
			
			// If the public signing and public encryption keys have their leading 0x04 byte in place then we need to remove them
			byte[] publicSigningKey = unencMsg.getPublicSigningKey();
			if (publicSigningKey[0] == (byte) 4  && publicSigningKey.length == 65)
			{
				publicSigningKey = ArrayCopier.copyOfRange(publicSigningKey, 1, publicSigningKey.length);
			}
			outputStream.write(publicSigningKey);
			
			byte[] publicEncryptionKey = unencMsg.getPublicEncryptionKey();
			if (publicEncryptionKey[0] == (byte) 4  && publicEncryptionKey.length == 65)
			{
				publicEncryptionKey = ArrayCopier.copyOfRange(publicEncryptionKey, 1, publicEncryptionKey.length);
			}
			outputStream.write(publicEncryptionKey);
			
			if (unencMsg.getSenderAddressVersion() >= 3) // The nonceTrialsPerByte and extraBytes fields are only included when the address version is >= 3
			{
				outputStream.write(VarintEncoder.encode(unencMsg.getNonceTrialsPerByte()));
				outputStream.write(VarintEncoder.encode(unencMsg.getExtraBytes()));
			}
		
			// For the purposes of signature payloads, the ripe hash must always be 20 bytes in length. 
			// Therefore if it is less than 20 bytes in length, pad it with zero bytes until it is.
			byte[] ripeHash = unencMsg.getDestinationRipe();
			ripeHash = ByteUtils.padWithLeadingZeros(ripeHash, 20);
			outputStream.write(ripeHash);
			
			outputStream.write(VarintEncoder.encode(unencMsg.getEncoding())); 
			outputStream.write(VarintEncoder.encode(unencMsg.getMessageLength())); 
			outputStream.write(unencMsg.getMessage());
			outputStream.write(VarintEncoder.encode(unencMsg.getAckLength())); 
			outputStream.write(unencMsg.getAckMsg());
			
			payload = outputStream.toByteArray();
			outputStream.close();
		}
		catch (IOException e) 
		{
			throw new RuntimeException("IOException occurred in SigProcessor.createUnencryptedMsgSignaturePayload()", e);
		}
		
		return payload;
	}
	
	/**
	 * Checks whether a given ECDSA signature is valid. 
	 * 
	 * @param payloadToVerify - The payload which we want to verify the signature of
	 * @param signature - A byte[] containing the signature to be verified
	 * @param publicKey - The ECPublicKey object used to create the signature
	 * 
	 * @return A boolean indicating whether the pubkey's signature is valid or not
	 */
	public boolean verifySignature(byte[] payloadToVerify, byte[] signature, ECPublicKey publicKey)
	{
		boolean signatureValid = false;
		
		try 
		{
			Signature sig = Signature.getInstance(ALGORITHM, PROVIDER);
			sig.initVerify(publicKey);
			sig.update(payloadToVerify);
			signatureValid = sig.verify(signature);
		}
		catch (NoSuchAlgorithmException e) 
		{
			throw new RuntimeException("NoSuchAlgorithmException occurred in SigProcessor.verifySignature()", e);
		}
		catch (NoSuchProviderException e) 
		{
			throw new RuntimeException("NoSuchProviderException occurred in SigProcessor.verifySignature()", e);
		}
		catch (SignatureException e) 
		{
			throw new RuntimeException("SignatureException occurred in SigProcessor.verifySignature()", e);
		}
		catch (InvalidKeyException e) 
		{
			throw new RuntimeException("InvalidKeyException occurred in SigProcessor.verifySignature()", e);
		}
		
		if (signatureValid == false)
		{
			Log.e(TAG, "While running SigProc.verifySignature(), the following signature was found to be invalid:\n"
					 + "Invalid signature: " + ByteFormatter.byteArrayToHexString(signature) + "\n"
					 + "Length of invalid signature: " + signature.length + " bytes" + "\n"
					 + "Payload for which the signature was invalid: " + ByteFormatter.byteArrayToHexString(payloadToVerify));
		}
		
		return signatureValid;
	}
	
	/**
	 * Produces an ECDSA signature for a given payload, using a private key in Wallet Import
	 * Format to produce the signature.
	 * 
	 * @param payloadToSign - The payload to be signed
	 * @param wifPrivateKey - A String containing the private key which will be used to create the 
	 * signature, encoded in Bitcoin-style Wallet Import Format. 
	 * 
	 * @return A byte[] containing the newly created signature. 
	 */
	public byte[] signWithWIFKey(byte[] payloadToSign, String wifPrivateKey)
	{
		KeyConverter converter = new KeyConverter();
		ECPrivateKey privKey = converter.decodePrivateKeyFromWIF(wifPrivateKey);
		
		byte[] signature = sign(payloadToSign, privKey);
		
		return signature;
	}
	
	/**
	 * Produces a ECDSA signature for a given payload, using a private key to produce the signature. 
	 * 
	 * @param payloadToSign - The payload to be signed
	 * @param privateKey - The ECPrivateKey object which will be used to create the signature. 
	 * 
	 * @return A byte[] containing the newly created signature. 
	 */
	private byte[] sign(byte[] payloadToSign, ECPrivateKey privateKey)
	{
		byte [] signature = null;	
		try 
		{
			Signature sig = Signature.getInstance(ALGORITHM, PROVIDER);
			sig.initSign(privateKey, new SecureRandom());
			sig.update(payloadToSign);
			signature = sig.sign();
		} 
		catch (NoSuchAlgorithmException e) 
		{
			throw new RuntimeException("NoSuchAlgorithmException occurred in SigProcessor.sign()", e);
		}
		catch (NoSuchProviderException e) 
		{
			throw new RuntimeException("NoSuchProviderException occurred in SigProcessor.sign()", e);
		}
		catch (InvalidKeyException e) 
		{
			throw new RuntimeException("InvalidKeyException occurred in SigProcessor.sign()", e);
		}
		catch (SignatureException e) 
		{
			throw new RuntimeException("SignatureException occurred in SigProcessor.sign()", e);
		}
	
		return signature;
	}
}