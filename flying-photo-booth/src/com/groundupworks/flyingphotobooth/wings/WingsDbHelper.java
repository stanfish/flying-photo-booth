package com.groundupworks.flyingphotobooth.wings;

import java.util.ArrayList;
import java.util.List;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

/**
 * The Wings database helper that stores {@link ShareRequest} records and manages the state of those records.
 * 
 * @author Benedict Lau
 */
public class WingsDbHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "wings.db";

    private static final int DB_VERSION = 1;

    private static final long ID_ERROR = -1L;

    //
    // SQL where clauses.
    //

    /**
     * SQL where clause by id.
     */
    private static final String WHERE_CLAUSE_BY_ID = ShareRequestTable.COLUMN_ID + "=?";

    /**
     * SQL where clause by destination and state.
     */
    private static final String WHERE_CLAUSE_BY_DESTINATION_AND_STATE = ShareRequestTable.COLUMN_DESTINATION
            + "=? AND " + ShareRequestTable.COLUMN_STATE + "=?";

    /**
     * SQL where clause that describes the purge policy. A query with this where clause will return all records
     * satisfying one or more of the following conditions:
     * 
     * <pre>
     * 1. Records created before a certain time
     * 2. Records in a certain state
     * 3. Records that failed more than a certain number of times
     * </pre>
     */
    private static final String WHERE_CLAUSE_PURGE_POLICY = ShareRequestTable.COLUMN_TIME_CREATED + "<? OR "
            + ShareRequestTable.COLUMN_STATE + "=? OR " + ShareRequestTable.COLUMN_FAILS + ">?";

    /**
     * SQL sort order by creation time of creation, from earliest to the most recent.
     */
    private static final String SORT_ORDER_TIME_CREATED = ShareRequestTable.COLUMN_TIME_CREATED + " ASC";

    //
    // Purge policy params.
    //

    /**
     * Records expire after 2 days. In milliseconds.
     */
    private static long RECORD_EXPIRY_TIME = 172800000L;

    /**
     * The number of times a record may fail to process, beyond which it will be purged. Too small of a number is
     * dangerous as every new record creation will trigger a retry, and the number of fails can build up quickly when
     * the device has no connectivity.
     */
    private static int RECORD_MAX_FAILS = 500;

    /**
     * Singleton.
     */
    private static WingsDbHelper sInstance;

    /**
     * Private constructor.
     * 
     * @param context
     *            the {@link Context}.
     */
    private WingsDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(ShareRequestTable.CREATE_SQL);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Do nothing.
    }

    //
    // Public methods.
    //

    /**
     * Gets the {@link WingsDbHelper} singleton.
     * 
     * @param context
     *            the {@link Context}.
     * @return the singleton.
     */
    public synchronized static final WingsDbHelper getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new WingsDbHelper(context.getApplicationContext());
        }
        return sInstance;
    }

    /**
     * Creates a new {@link ShareRequest}.
     * 
     * @param filePath
     *            the local path to the file to share.
     * @param destination
     *            the destination of the share.
     * @return true if successful; false otherwise.
     */
    public synchronized boolean createShareRequest(String filePath, int destination) {
        boolean isSuccessful = false;

        SQLiteDatabase db = null;
        try {
            db = getWritableDatabase();

            // Create new record.
            ContentValues values = new ContentValues();
            values.put(ShareRequestTable.COLUMN_FILE_PATH, filePath);
            values.put(ShareRequestTable.COLUMN_DESTINATION, destination);
            values.put(ShareRequestTable.COLUMN_TIME_CREATED, System.currentTimeMillis());
            values.put(ShareRequestTable.COLUMN_STATE, ShareRequest.STATE_PENDING);
            values.put(ShareRequestTable.COLUMN_FAILS, 0);

            isSuccessful = db.insert(ShareRequestTable.NAME, null, values) != ID_ERROR;

            Log.d(getClass().getSimpleName(), "createShareRequest() isSuccessful=" + isSuccessful + " filePath="
                    + filePath + " destination=" + destination);
        } catch (SQLException e) {
            // Do nothing.
        } finally {
            db.close();
        }

        return isSuccessful;
    }

    /**
     * Checks out a list of {@link ShareRequest} that need to be processed, filtered by destination. The list is sorted
     * by time of creation, from the earliest to most recent. This method internally changes the checked out records to
     * a processing state, so a call to {@link #markSuccessful(int)} or {@link #markFailed(int)} is expected to be
     * called on each of those records.
     * 
     * @param destination
     *            the destination of the {@link ShareRequest} to checkout.
     * @return the list of {@link ShareRequest}; may be empty.
     */
    public synchronized List<ShareRequest> checkoutShareRequests(int destination) {
        List<ShareRequest> shareRequests = new ArrayList<ShareRequest>();

        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = getWritableDatabase();

            // Get all records for the requested destination in the pending state.
            cursor = db.query(ShareRequestTable.NAME, new String[] { ShareRequestTable.COLUMN_ID,
                    ShareRequestTable.COLUMN_FILE_PATH }, WHERE_CLAUSE_BY_DESTINATION_AND_STATE,
                    new String[] { String.valueOf(destination), String.valueOf(ShareRequest.STATE_PENDING) }, null,
                    null, SORT_ORDER_TIME_CREATED);

            if (cursor != null && cursor.moveToFirst()) {
                do {
                    int id = cursor.getInt(cursor.getColumnIndex(ShareRequestTable.COLUMN_ID));
                    String filePath = cursor.getString(cursor.getColumnIndex(ShareRequestTable.COLUMN_FILE_PATH));

                    // Update state back to processing.
                    ContentValues values = new ContentValues();
                    values.put(ShareRequestTable.COLUMN_STATE, ShareRequest.STATE_PROCESSING);

                    if (db.update(ShareRequestTable.NAME, values, WHERE_CLAUSE_BY_ID,
                            new String[] { String.valueOf(id) }) > 0) {
                        // Add record to list.
                        shareRequests.add(new ShareRequest(id, filePath, destination));

                        Log.d(getClass().getSimpleName(), "checkoutShareRequests() id=" + id + " filePath=" + filePath
                                + " destination=" + destination);
                    }
                } while (cursor.moveToNext());
            }
        } catch (SQLException e) {
            // Do nothing.
        } finally {
            if (cursor != null) {
                cursor.close();
            }
            db.close();
        }

        return shareRequests;
    }

    /**
     * Marks a {@link ShareRequest} as successfully processed.
     * 
     * @param id
     *            the id of the {@link ShareRequest}.
     * @return true if successful; false otherwise.
     */
    public synchronized boolean markSuccessful(int id) {
        boolean isSuccessful = false;

        SQLiteDatabase db = null;
        try {
            db = getWritableDatabase();

            // Set state to processed.
            ContentValues values = new ContentValues();
            values.put(ShareRequestTable.COLUMN_STATE, ShareRequest.STATE_PROCESSED);

            isSuccessful = db.update(ShareRequestTable.NAME, values, WHERE_CLAUSE_BY_ID,
                    new String[] { String.valueOf(id) }) > 0;

            Log.d(getClass().getSimpleName(), "markSuccessful() isSuccessful=" + isSuccessful + " id=" + id);
        } catch (SQLException e) {
            // Do nothing.
        } finally {
            db.close();
        }
        return isSuccessful;
    }

    /**
     * Marks a {@link ShareRequest} as failed to process.
     * 
     * @param id
     *            the id of the {@link ShareRequest}.
     * @return true if successful; false otherwise.
     */
    public synchronized boolean markFailed(int id) {
        boolean isSuccessful = false;

        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = getWritableDatabase();

            // Get number of times this record has already failed to process.
            cursor = db.query(ShareRequestTable.NAME, new String[] { ShareRequestTable.COLUMN_FAILS },
                    WHERE_CLAUSE_BY_ID, new String[] { String.valueOf(id) }, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int fails = cursor.getInt(cursor.getColumnIndex(ShareRequestTable.COLUMN_FAILS));

                // Reset state back to pending and increment fails.
                ContentValues values = new ContentValues();
                values.put(ShareRequestTable.COLUMN_STATE, ShareRequest.STATE_PENDING);
                values.put(ShareRequestTable.COLUMN_FAILS, fails + 1);

                isSuccessful = db.update(ShareRequestTable.NAME, values, WHERE_CLAUSE_BY_ID,
                        new String[] { String.valueOf(id) }) > 0;

                Log.d(getClass().getSimpleName(), "markFailed() isSuccessful=" + isSuccessful + " id=" + id);
            }
        } catch (SQLException e) {
            // Do nothing.
        } finally {
            if (cursor != null) {
                cursor.close();
            }
            db.close();
        }

        return isSuccessful;
    }

    /**
     * Purges the database based on the purge policy.
     */
    public synchronized void purge() {
        SQLiteDatabase db = null;
        try {
            db = getWritableDatabase();

            long earliestValidTime = System.currentTimeMillis() - RECORD_EXPIRY_TIME;
            int rows = db.delete(ShareRequestTable.NAME, WHERE_CLAUSE_PURGE_POLICY,
                    new String[] { String.valueOf(earliestValidTime), String.valueOf(ShareRequest.STATE_PROCESSED),
                            String.valueOf(RECORD_MAX_FAILS) });

            Log.d(getClass().getSimpleName(), "purge() rows=" + rows);
        } catch (SQLException e) {
            // Do nothing.
        } finally {
            db.close();
        }
    }

    //
    // Private classes.
    //

    /**
     * Table with each record representing a share request that needs to be processed.
     */
    private static class ShareRequestTable {

        /**
         * Table name.
         */
        private static final String NAME = "shares";

        /**
         * SQL statement to create table.
         */
        private static final String CREATE_SQL = String
                .format("CREATE TABLE %s (%s INTEGER PRIMARY KEY AUTOINCREMENT, %s TEXT NOT NULL, %s INTEGER NOT NULL, %s INTEGER NOT NULL, %s INTEGER NOT NULL, %s INTEGER NOT NULL)",
                        ShareRequestTable.NAME, ShareRequestTable.COLUMN_ID, ShareRequestTable.COLUMN_FILE_PATH,
                        ShareRequestTable.COLUMN_DESTINATION, ShareRequestTable.COLUMN_TIME_CREATED,
                        ShareRequestTable.COLUMN_STATE, ShareRequestTable.COLUMN_FAILS);

        //
        // Columns names.
        //

        /**
         * The record id.
         */
        private static final String COLUMN_ID = "_id";

        /**
         * The local path to the file to share.
         */
        private static final String COLUMN_FILE_PATH = "file_path";

        /**
         * The destination of the share.
         */
        private static final String COLUMN_DESTINATION = "destination";

        /**
         * The time the record is created. Internally managed.
         */
        private static final String COLUMN_TIME_CREATED = "time_created";

        /**
         * The current state of the share. Internally managed.
         */
        private static final String COLUMN_STATE = "state";

        /**
         * The number of times sharing failed. Internally managed.
         */
        private static final String COLUMN_FAILS = "fails";
    }
}