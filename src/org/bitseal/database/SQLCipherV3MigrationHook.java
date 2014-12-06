/** 
CacheWord License:
This file contains the license for CacheWord.
For more information about CacheWord, see https://guardianproject.info/
If you got this file as a part of a larger bundle, there may be other
license terms that you should be aware of.
===============================================================================
CacheWord is distributed under this license (aka the 3-clause BSD license)
Copyright (C) 2013-2014 Abel Luck <abel@guardianproject.info>
Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:
* Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
* Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
* Neither the names of the copyright owners nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.
THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. 
*/
package org.bitseal.database;

import info.guardianproject.cacheword.Constants;
import net.sqlcipher.database.SQLiteDatabase;
import net.sqlcipher.database.SQLiteDatabaseHook;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * This hook handles the v2 -> v3 migration for SQLCipher databases
 * 
 * @author Abel Luck, modified by Jonathan Coe
 */
public class SQLCipherV3MigrationHook implements SQLiteDatabaseHook
{
    private Context mContext;

    public SQLCipherV3MigrationHook(Context context)
    {
        mContext = context;
    }

    @Override
    public void preKey(SQLiteDatabase database)
    {
        // nop for now
    }

    @Override
    public void postKey(SQLiteDatabase database)
    {
        /* V2 - V3 migration */
        if (!isMigratedV3(mContext, database)) 
        {
            database.rawExecSQL("PRAGMA cipher_migrate;");
            setMigratedV3(mContext, database, true);
        }

    }

    public static void setMigratedV3(Context context, SQLiteDatabase database, boolean migrated)
    {
        SharedPreferences prefs = context.getSharedPreferences(Constants.SHARED_PREFS_SQLCIPHER_V3_MIGRATE, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(database.getPath(), migrated).commit();
    }

    public static boolean isMigratedV3(Context context, SQLiteDatabase database)
    {
        SharedPreferences prefs = context.getSharedPreferences(Constants.SHARED_PREFS_SQLCIPHER_V3_MIGRATE, Context.MODE_PRIVATE);
        return prefs.getBoolean(database.getPath(), false);
    }
}