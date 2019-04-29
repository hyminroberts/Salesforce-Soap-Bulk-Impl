/*
 * Copyright (c) 2019 Etix / Intellimark, Inc - All Rights Reserved.
 * This file is part of 'web-app', unauthorized copying of this file, via any medium is strictly prohibited.
 */

package com.etix.api.salesforce;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sforce.async.AsyncApiException;
import com.sforce.async.BatchInfo;
import com.sforce.async.BatchStateEnum;
import com.sforce.async.BulkConnection;
import com.sforce.async.CSVReader;
import com.sforce.async.ContentType;
import com.sforce.async.JobInfo;
import com.sforce.async.JobStateEnum;
import com.sforce.async.OperationEnum;
import com.sforce.soap.enterprise.Connector;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.ConnectorConfig;

/**
 * Manager class for integration with Salesforce Bulk APIs.
 * @author Zhonghui Luo
 * @since Apr 1st, 2019 / DEV-4309
 */
public class SalesforceBulkManager {
    /**
     * Creates a Bulk API job and uploads batches for a CSV file.
     * @param sobjectType object name like 'Account'
     * @param userName username
     * @param password password
     * @param authEndPoint authenticated end point
     * @param fileName file name
     * @throws com.sforce.async.AsyncApiException when exception encountered on saving objects to Salesforce
     * @throws com.sforce.ws.ConnectionException when exception encountered on connecting to Salesforce platform
     * @throws java.io.IOException when exception encountered on loading batch file
     */
    public void createObjectsInBatch(String sobjectType, String userName, String password, String authEndPoint, String fileName) throws AsyncApiException, ConnectionException, IOException {
        BulkConnection connection = getBulkConnection(userName, password, authEndPoint);
        JobInfo job = createJob(sobjectType, connection);
        List<BatchInfo> batchInfoList = createBatchesFromCsvFile(connection, job, fileName);
        closeJob(connection, job.getId());
        awaitCompletion(connection, job, batchInfoList);
        checkResults(connection, job, batchInfoList);
    }

    /**
     * Create the BulkConnection used to call Bulk API operations.
     */
    private BulkConnection getBulkConnection(String userName, String password, String authEndPoint) throws ConnectionException, AsyncApiException {
        ConnectorConfig enterpriseConfig = new ConnectorConfig();
        enterpriseConfig.setUsername(userName);
        enterpriseConfig.setPassword(password);
        enterpriseConfig.setAuthEndpoint(authEndPoint);
        // Creating the connection automatically handles login and stores
        // the session in partnerConfig
        Connector.newConnection(enterpriseConfig);
        // When PartnerConnection is instantiated, a login is implicitly
        // executed and, if successful,
        // a valid session is stored in the ConnectorConfig instance.
        // Use this key to initialize a BulkConnection:
        ConnectorConfig config = new ConnectorConfig();
        config.setSessionId(enterpriseConfig.getSessionId());
        // The endpoint for the Bulk API service is the same as for the normal
        // SOAP uri until the /Soap/ part. From here it's '/async/versionNumber'
        String soapEndpoint = enterpriseConfig.getServiceEndpoint();
        String apiVersion = "45.0";
        String restEndpoint = soapEndpoint.substring(0, soapEndpoint.indexOf("Soap/")) + "async/" + apiVersion;
        config.setRestEndpoint(restEndpoint);
        // This should only be false when doing debugging.
        config.setCompression(true);
        // Set this to true to see HTTP requests and responses on stdout
        config.setTraceMessage(false);
        BulkConnection connection = new BulkConnection(config);
        return connection;
    }

    /**
     * Create a new job using the Bulk API.
     *
     * @param sobjectType The object type being loaded, such as "Account"
     * @param connection BulkConnection used to create the new job.
     * @return The JobInfo for the new job.
     * @throws AsyncApiException when exception encountered on creating batch job.
     */
    private JobInfo createJob(String sobjectType, BulkConnection connection) throws AsyncApiException {
        JobInfo job = new JobInfo();
        job.setObject(sobjectType);
        job.setOperation(OperationEnum.insert);
        job.setContentType(ContentType.CSV);
        job = connection.createJob(job);
        System.out.println(job);
        return job;
    }

    /**
     * Create and upload batches using a CSV file. The file into the appropriate size batch files.
     *
     * @param connection Connection to use for creating batches
     * @param jobInfo Job associated with new batches
     * @param csvFileName The source file for batch data
     */
    private List<BatchInfo> createBatchesFromCsvFile(BulkConnection connection, JobInfo jobInfo, String csvFileName) throws IOException, AsyncApiException {
        List<BatchInfo> batchInfos = new ArrayList<>();
        BufferedReader rdr = new BufferedReader(new InputStreamReader(new FileInputStream(csvFileName)));
        // read the CSV header row
        byte[] headerBytes = (rdr.readLine() + "\n").getBytes("UTF-8");
        int headerBytesLength = headerBytes.length;
        File tmpFile = File.createTempFile("bulkAPIInsert", ".csv");

        // Split the CSV file into multiple batches
        try {
            FileOutputStream tmpOut = new FileOutputStream(tmpFile);
            int maxBytesPerBatch = 10000000; // 10 million bytes per batch
            int maxRowsPerBatch = 10000; // 10 thousand rows per batch
            int currentBytes = 0;
            int currentLines = 0;
            String nextLine;
            while ((nextLine = rdr.readLine()) != null) {
                byte[] bytes = (nextLine + "\n").getBytes("UTF-8");
                // Create a new batch when our batch size limit is reached
                if (currentBytes + bytes.length > maxBytesPerBatch || currentLines > maxRowsPerBatch) {
                    createBatch(tmpOut, tmpFile, batchInfos, connection, jobInfo);
                    currentBytes = 0;
                    currentLines = 0;
                }
                if (currentBytes == 0) {
                    tmpOut = new FileOutputStream(tmpFile);
                    tmpOut.write(headerBytes);
                    currentBytes = headerBytesLength;
                    currentLines = 1;
                }
                tmpOut.write(bytes);
                currentBytes += bytes.length;
                currentLines++;
            }
            // Finished processing all rows
            // Create a final batch for any remaining data
            if (currentLines > 1) {
                createBatch(tmpOut, tmpFile, batchInfos, connection, jobInfo);
            }
        } finally {
            tmpFile.delete();
        }
        return batchInfos;
    }

    /**
     * Create a batch by uploading the contents of the file. This closes the output stream.
     *
     * @param tmpOut The output stream used to write the CSV data for a single batch.
     * @param tmpFile The file associated with the above stream.
     * @param batchInfos The batch info for the newly created batch is added to this list.
     * @param connection The BulkConnection used to create the new batch.
     * @param jobInfo The JobInfo associated with the new batch.
     */
    private void createBatch(FileOutputStream tmpOut, File tmpFile, List<BatchInfo> batchInfos, BulkConnection connection, JobInfo jobInfo) throws IOException, AsyncApiException {
        tmpOut.flush();
        tmpOut.close();
        FileInputStream tmpInputStream = new FileInputStream(tmpFile);
        try {
            BatchInfo batchInfo = connection.createBatchFromStream(jobInfo, tmpInputStream);
            System.out.println(batchInfo);
            batchInfos.add(batchInfo);
        } finally {
            tmpInputStream.close();
        }
    }

    private void closeJob(BulkConnection connection, String jobId) throws AsyncApiException {
        JobInfo job = new JobInfo();
        job.setId(jobId);
        job.setState(JobStateEnum.Closed);
        connection.updateJob(job);
    }

    /**
     * Wait for a job to complete by polling the Bulk API.
     *
     * @param connection BulkConnection used to check results.
     * @param job The job awaiting completion.
     * @param batchInfoList List of batches for this job.
     * @throws AsyncApiException when exception encountered
     */
    private void awaitCompletion(BulkConnection connection, JobInfo job, List<BatchInfo> batchInfoList) throws AsyncApiException {
        long sleepTime = 0L;
        Set<String> incomplete = new HashSet();
        for (BatchInfo bi : batchInfoList) {
            incomplete.add(bi.getId());
        }
        while (!incomplete.isEmpty()) {
            try {
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                System.out.println("TTTTTTTTTTTTTTTT: Interruppting exception" + e.getMessage() + " TTTTTTTTTTTTT");
            }
            System.out.println("TTTTTTTTTTTTTTT: Awaiting results..." + incomplete.size() + " TTTTTTTTTTTT");
            sleepTime = 10000L;
            BatchInfo[] statusList = connection.getBatchInfoList(job.getId()).getBatchInfo();
            for (BatchInfo b : statusList) {
                if (b.getState() == BatchStateEnum.Completed || b.getState() == BatchStateEnum.Failed) {
                    if (incomplete.remove(b.getId())) {
                        System.out.println("BATCH STATUS:\n" + b);
                    }
                }
            }
        }
    }

    /**
     * Gets the results of the operation and checks for errors.
     */
    private void checkResults(BulkConnection connection, JobInfo job, List<BatchInfo> batchInfoList) throws AsyncApiException, IOException {
        // batchInfoList was populated when batches were created and submitted
        for (BatchInfo b : batchInfoList) {
            CSVReader rdr = new CSVReader(connection.getBatchResultStream(job.getId(), b.getId()));
            List<String> resultHeader = rdr.nextRecord();
            int resultCols = resultHeader.size();

            List<String> row;
            while ((row = rdr.nextRecord()) != null) {
                Map<String, String> resultInfo = new HashMap();
                for (int i = 0; i < resultCols; i++) {
                    resultInfo.put(resultHeader.get(i), row.get(i));
                }
                boolean success = Boolean.valueOf(resultInfo.get("Success"));
                boolean created = Boolean.valueOf(resultInfo.get("Created"));
                String id = resultInfo.get("Id");
                String error = resultInfo.get("Error");
                if (success && created) {
                    System.out.println("Created row with id " + id);
                } else if (!success) {
                    System.out.println("Failed with error: " + error);
                }
            }
        }
    }

}
