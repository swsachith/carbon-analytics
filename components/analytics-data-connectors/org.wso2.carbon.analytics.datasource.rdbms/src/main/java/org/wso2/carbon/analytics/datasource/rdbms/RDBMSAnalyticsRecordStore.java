/*
 *  Copyright (c) 2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.wso2.carbon.analytics.datasource.rdbms;

import com.google.common.collect.Lists;
import org.wso2.carbon.analytics.datasource.commons.AnalyticsIterator;
import org.wso2.carbon.analytics.datasource.commons.Record;
import org.wso2.carbon.analytics.datasource.commons.RecordGroup;
import org.wso2.carbon.analytics.datasource.commons.exception.AnalyticsException;
import org.wso2.carbon.analytics.datasource.commons.exception.AnalyticsTableNotAvailableException;
import org.wso2.carbon.analytics.datasource.core.rs.AnalyticsRecordStore;
import org.wso2.carbon.analytics.datasource.core.util.GenericUtils;
import org.wso2.carbon.ndatasource.common.DataSourceException;

import javax.sql.DataSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

/**
 * Abstract RDBMS database backed implementation of {@link AnalyticsRecordStore}.
 */
public class RDBMSAnalyticsRecordStore implements AnalyticsRecordStore {
    
    private static final String RECORD_IDS_PLACEHOLDER = "{{RECORD_IDS}}";

    private static final String TABLE_NAME_PLACEHOLDER = "{{TABLE_NAME}}";
        
    private DataSource dataSource;
    
    private Map<String, String> properties;
    
    private RDBMSQueryConfigurationEntry rdbmsQueryConfigurationEntry;
    
    private int partitionCount = RDBMSAnalyticsDSConstants.DEFAULT_PARTITION_COUNT;
    
    public RDBMSAnalyticsRecordStore() throws AnalyticsException {
        this.rdbmsQueryConfigurationEntry = null;
    }
    
    @Override
    public void init(Map<String, String> properties)
            throws AnalyticsException {
        this.properties = properties;
        String dsName = properties.get(RDBMSAnalyticsDSConstants.DATASOURCE);
        if (dsName == null) {
            throw new AnalyticsException("The property '" + 
                    RDBMSAnalyticsDSConstants.DATASOURCE + "' is required");
        }
        String partitionCountProp = properties.get(RDBMSAnalyticsDSConstants.PARTITION_COUNT);
        if (partitionCountProp != null) {
            this.partitionCount = Integer.parseInt(partitionCountProp);
        }
        try {
            this.dataSource = (DataSource) GenericUtils.loadGlobalDataSource(dsName);
        } catch (DataSourceException e) {
            throw new AnalyticsException("Error in loading data source: " + e.getMessage(), e);
        }
        if (this.rdbmsQueryConfigurationEntry == null) {
            String category = properties.get(RDBMSAnalyticsDSConstants.CATEGORY);
            this.rdbmsQueryConfigurationEntry = RDBMSUtils.lookupCurrentQueryConfigurationEntry(this.dataSource, category);
        }
    }
        
    public RDBMSQueryConfigurationEntry getQueryConfiguration() {
        return rdbmsQueryConfigurationEntry;
    }
    
    public int getPartitionCount() {
        return partitionCount;
    }
    
    private String[] getRecordTableInitQueries(int tenantId, String tableName) {
        String[] queries = this.getQueryConfiguration().getRecordTableInitQueries();
        String[] result = new String[queries.length];
        for (int i = 0; i < queries.length; i++) {
            result[i] = this.translateQueryWithTableInfo(queries[i], tenantId, tableName);
        }
        return result;
    }
    
    private String[] getRecordTableDeleteQueries(int tenantId, String tableName) {
        String[] queries = this.getQueryConfiguration().getRecordTableDeleteQueries();
        String[] result = new String[queries.length];
        for (int i = 0; i < queries.length; i++) {
            result[i] = this.translateQueryWithTableInfo(queries[i], tenantId, tableName);
        }
        return result;
    }
    
    public Map<String, String> getProperties() {
        return properties;
    }
    
    public DataSource getDataSource() {
        return dataSource;
    }
    
    private Connection getConnection() throws SQLException {
        return this.getConnection(true);
    }
    
    private Connection getConnection(boolean autoCommit) throws SQLException {
        Connection conn = this.getDataSource().getConnection();
        conn.setAutoCommit(autoCommit);
        return conn;
    }
    
    @Override
    public void put(List<Record> records) throws AnalyticsException, AnalyticsTableNotAvailableException {        
        if (records.size() == 0) {
            return;
        }
        Connection conn = null;
        try {
            conn = this.getConnection(false);
            Collection<List<Record>> recordBatches = GenericUtils.generateRecordBatches(records);
            for (List<Record> batch : recordBatches) {
                this.addRecordsSimilar(conn, batch);
            }
            conn.commit();
        } catch (SQLException e) {
            RDBMSUtils.rollbackConnection(conn);
            throw new AnalyticsException("Error in adding records: " + e.getMessage(), e);
        } catch (AnalyticsException e) {
            RDBMSUtils.rollbackConnection(conn);
            throw e;
        } finally {
            RDBMSUtils.cleanupConnection(null, null, conn);
        }
    }
    
    private void addRecordsSimilar(Connection conn, 
            List<Record> records) throws SQLException, 
            AnalyticsException, AnalyticsTableNotAvailableException {
        Record firstRecord = records.get(0);
        int tenantId = firstRecord.getTenantId();
        String tableName = firstRecord.getTableName();
        String mergeSQL = this.getRecordMergeSQL(tenantId, tableName);
        if (mergeSQL != null) {
            this.mergeRecordsSimilar(conn, records, tenantId, tableName, mergeSQL);
        } else {
            this.insertAndUpdateRecordsSimilar(conn, records, tenantId, tableName);
        }
    }
    
    private int generatePartitionKey(String id) {
        return Math.abs(id.hashCode()) % this.getPartitionCount();
    }
    
    private void populateStatementForAdd(PreparedStatement stmt, 
            Record record) throws SQLException, AnalyticsException {
        stmt.setInt(1, this.generatePartitionKey(record.getId()));
        stmt.setLong(2, record.getTimestamp());
        byte [] bytes = GenericUtils.encodeRecordValues(record.getValues());
        if (!this.rdbmsQueryConfigurationEntry.isBlobLengthRequired()) {
            stmt.setBinaryStream(3, new ByteArrayInputStream(bytes));
        } else {
            stmt.setBinaryStream(3, new ByteArrayInputStream(bytes), bytes.length);
        }
        stmt.setString(4, record.getId());
    }
    
    private void mergeRecordsSimilar(Connection conn, 
            List<Record> records, int tenantId, String tableName, String query) 
            throws SQLException, AnalyticsException, AnalyticsTableNotAvailableException {
        PreparedStatement stmt = null;
        try {
            stmt = conn.prepareStatement(query);
            for (Record record : records) {
                this.populateStatementForAdd(stmt, record);
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (SQLException e) {
            RDBMSUtils.rollbackConnection(conn);
            if (!this.tableExists(conn, tenantId, tableName)) {
                throw new AnalyticsTableNotAvailableException(tenantId, tableName);
            } else {
                throw e;
            }
        } finally {
            RDBMSUtils.cleanupConnection(null, stmt, null);
        }
    }
    
    private void insertAndUpdateRecordsSimilar(Connection conn, 
            List<Record> records, int tenantId, String tableName) throws SQLException, 
            AnalyticsException, AnalyticsTableNotAvailableException {
        try {
            this.insertBatchRecordsSimilar(conn, records, tenantId, tableName);
        } catch (SQLException e) {
            /* batch insert failed, maybe because one of the records were already there,
             * lets try to sequentially insert/update */
            this.insertAndUpdateRecordsSimilarSequentially(conn, records, tenantId, tableName);
        } catch (AnalyticsException e) {
            throw e;
        }
    }
    
    private void insertAndUpdateRecordsSimilarSequentially(Connection conn, 
            List<Record> records, int tenantId, String tableName) throws SQLException, AnalyticsException {
        String insertQuery = this.getRecordInsertSQL(tenantId, tableName);
        String updateQuery = this.getRecordUpdateSQL(tenantId, tableName);
        PreparedStatement stmt = null;
        for (Record record : records) {
            stmt = conn.prepareStatement(insertQuery);
            this.populateStatementForAdd(stmt, record);
            try {
                stmt.executeUpdate();
                conn.commit();
            } catch (SQLException e) {
                /* maybe the record is already there, lets try to update */
                RDBMSUtils.rollbackConnection(conn);
                stmt.close();
                stmt = conn.prepareStatement(updateQuery);
                this.populateStatementForAdd(stmt, record);
                stmt.executeUpdate();
                conn.commit();
            }
        }        
    }
    
    private void insertBatchRecordsSimilar(Connection conn, 
            List<Record> records, int tenantId, String tableName) throws SQLException, 
            AnalyticsException, AnalyticsTableNotAvailableException {
        String query = this.getRecordInsertSQL(tenantId, tableName);
        PreparedStatement stmt = null;
        try {
            stmt = conn.prepareStatement(query);
            for (Record record : records) {
                this.populateStatementForAdd(stmt, record);
                stmt.addBatch();
            }
            stmt.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            RDBMSUtils.rollbackConnection(conn);
            if (!this.tableExists(conn, tenantId, tableName)) {
                throw new AnalyticsTableNotAvailableException(tenantId, tableName);
            } else {
                throw e;
            }
        } finally {
            RDBMSUtils.cleanupConnection(null, stmt, null);
        }
    }
    
    private String getRecordMergeSQL(int tenantId, String tableName) {
    	String query = this.getQueryConfiguration().getRecordMergeQuery();
    	return translateQueryWithTableInfo(query, tenantId, tableName);
    }
    
    private String getRecordInsertSQL(int tenantId, String tableName) {
        String query = this.getQueryConfiguration().getRecordInsertQuery();
        return translateQueryWithTableInfo(query, tenantId, tableName);
    }
    
    private String getRecordUpdateSQL(int tenantId, String tableName) {
        String query = this.getQueryConfiguration().getRecordUpdateQuery();
        return translateQueryWithTableInfo(query, tenantId, tableName);
    }
    
    private boolean tableExists(int tenantId, String tableName) throws AnalyticsException {
        Connection conn = null;
        try {
            conn = this.getConnection();
            return this.tableExists(conn, tenantId, tableName);
        } catch (SQLException e) {
            throw new AnalyticsException("Error in tableExists: " + e.getMessage(), e);
        } finally {            
            RDBMSUtils.cleanupConnection(null, null, conn);
        }
    }
    
    @Override
    public RecordGroup[] get(int tenantId, String tableName, int numPartitionsHint, List<String> columns, 
            List<String> ids) throws AnalyticsException,
            AnalyticsTableNotAvailableException {
        if (!this.tableExists(tenantId, tableName)) {
            throw new AnalyticsTableNotAvailableException(tenantId, tableName);
        }
        return new RDBMSIDsRecordGroup[] { new RDBMSIDsRecordGroup(tenantId, tableName, columns, ids) };
    }
    
    private List<Integer[]> generatePartitionPlan(int numPartitionsHint) throws AnalyticsException, 
            AnalyticsTableNotAvailableException {
        List<Integer[]> result = GenericUtils.splitNumberRange(this.getPartitionCount(), numPartitionsHint);
        for (Integer[] entry : result) {
            entry[1] = entry[0] + entry[1];
        }
        return result;
    }
    
    @Override
    public RecordGroup[] get(int tenantId, String tableName, int numPartitionsHint, List<String> columns,
            long timeFrom, long timeTo, int recordsFrom, int recordsCount)
            throws AnalyticsException, AnalyticsTableNotAvailableException {
        if (!this.tableExists(tenantId, tableName)) {
            throw new AnalyticsTableNotAvailableException(tenantId, tableName);
        }
        if (numPartitionsHint > 1 && (recordsFrom > 0 || (recordsCount != -1 && recordsCount != Integer.MAX_VALUE))) {
            numPartitionsHint = 1;
        }
        List<Integer[]> params = this.generatePartitionPlan(numPartitionsHint);
        RDBMSRangeRecordGroup[] result = new RDBMSRangeRecordGroup[params.size()];
        Integer[] param;
        for (int i = 0; i < result.length; i++) {
            param = params.get(i);
            result[i] = new RDBMSRangeRecordGroup(tenantId, tableName, columns, timeFrom, timeTo, 
                    recordsFrom, recordsCount, param[0], param[1]);
        }
        return result;
    }

    @Override
    public AnalyticsIterator<Record> readRecords(RecordGroup recordGroup) throws AnalyticsException {
        if (recordGroup instanceof RDBMSRangeRecordGroup) {
            RDBMSRangeRecordGroup recordRangeGroup = (RDBMSRangeRecordGroup) recordGroup;
            return this.getRecords(recordRangeGroup.getTenantId(), recordRangeGroup.getTableName(), 
                    recordRangeGroup.getColumns(), recordRangeGroup.getTimeFrom(), 
                    recordRangeGroup.getTimeTo(), recordRangeGroup.getRecordsFrom(), recordRangeGroup.getRecordsCount(),
                    recordRangeGroup.getPartitionStart(), recordRangeGroup.getPartitionEnd());
        } else if (recordGroup instanceof RDBMSIDsRecordGroup) {
            RDBMSIDsRecordGroup recordIdGroup = (RDBMSIDsRecordGroup) recordGroup;
            return this.getRecords(recordIdGroup.getTenantId(), recordIdGroup.getTableName(), 
                    recordIdGroup.getColumns(), recordIdGroup.getIds());
        } else {
            throw new AnalyticsException("Invalid RDBMS RecordGroup implementation: " + recordGroup.getClass());
        }
    }
    
    public AnalyticsIterator<Record> getRecords(int tenantId, String tableName, List<String> columns,
                                                long timeFrom, long timeTo, int recordsFrom,
                                                int recordsCount, int partitionStart, 
                                                int partitionEnd) throws AnalyticsException, 
                                                AnalyticsTableNotAvailableException {
        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            conn = this.getConnection(false);
            if (!this.rdbmsQueryConfigurationEntry.isForwardOnlyReadEnabled()) {
                stmt = conn.prepareStatement(this.getRecordRetrievalQuery(tenantId, tableName));
            } else {
                stmt = conn.prepareStatement(this.getRecordRetrievalQuery(tenantId, tableName),
                        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                stmt.setFetchSize(this.rdbmsQueryConfigurationEntry.getFetchSize());
            }
            if (recordsCount == -1) {
                recordsCount = Integer.MAX_VALUE;
            }
            stmt.setLong(1, partitionStart);
            stmt.setLong(2, partitionEnd);
            stmt.setLong(3, timeFrom);
            stmt.setLong(4, timeTo);
            int[] paginationIndices = this.calculateIndicesForPaginationMode(recordsFrom, recordsCount);
            stmt.setInt(5, paginationIndices[0]);
            stmt.setInt(6, paginationIndices[1]);
            rs = stmt.executeQuery();
            return new RDBMSResultSetIterator(tenantId, tableName, columns, conn, stmt, rs);
        } catch (SQLException e) {
            if (conn != null && !this.tableExists(conn, tenantId, tableName)) {
                RDBMSUtils.cleanupConnection(rs, stmt, conn);
                throw new AnalyticsTableNotAvailableException(tenantId, tableName);
            } else {
                RDBMSUtils.cleanupConnection(rs, stmt, conn);
                throw new AnalyticsException("Error in retrieving records: " + e.getMessage(), e);
            }            
        }
    }
    
    private int[] calculateIndicesForPaginationMode(int recordsFrom, int recordsCount) {
        switch (this.rdbmsQueryConfigurationEntry.getPaginationMode()) {
        case MODE1:
            /* MySQL, H2, MSSQL 2012 like */
            return new int[] { recordsFrom, recordsCount };
        case MODE2:
            /* Oracle, MSSQL ROWNUM like */
            return new int[] {recordsFrom + recordsCount, recordsFrom};
        case MODE3:
            /* inverse MODE2 */
            return new int[] {recordsFrom, recordsFrom + recordsCount};
        default:
            throw new IllegalArgumentException("Invalid pagination mode: " + 
                    this.rdbmsQueryConfigurationEntry.getPaginationMode());
        }
    }
    
    public AnalyticsIterator<Record> getRecords(int tenantId, String tableName, List<String> columns,
                                                List<String> ids) throws AnalyticsException, AnalyticsTableNotAvailableException {
        if (ids.isEmpty()) {
            return new EmptyResultSetAnalyticsIterator();
        }
        if (ids.size() > this.rdbmsQueryConfigurationEntry.getRecordBatchSize()) {
            List<List<String>> idsSubLists = Lists.partition(ids, this.rdbmsQueryConfigurationEntry.getRecordBatchSize());
            RDBMSIDsRecordGroup [] rdbmsIDsRecordGroups = new RDBMSIDsRecordGroup[idsSubLists.size()];
            int index = 0;
            for(List<String> idSubList : idsSubLists) {
                rdbmsIDsRecordGroups[index] =  new RDBMSIDsRecordGroup(tenantId, tableName, columns, idSubList);
                index++;
            }
            return new RDBMSRecordIDListIterator(this, rdbmsIDsRecordGroups);
        }
        String recordGetSQL = this.generateGetRecordRetrievalWithIdQuery(tenantId, tableName, ids.size());
        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            conn = this.getConnection();
            stmt = conn.prepareStatement(recordGetSQL);
            for (int i = 0; i < ids.size(); i++) {
                stmt.setString(i + 1, ids.get(i));
            }
            rs = stmt.executeQuery();
            return new RDBMSResultSetIterator(tenantId, tableName, columns, conn, stmt, rs);
        } catch (SQLException e) {
            if (conn != null && !this.tableExists(conn, tenantId, tableName)) {
                RDBMSUtils.cleanupConnection(rs, stmt, conn);
                throw new AnalyticsTableNotAvailableException(tenantId, tableName);
            } else {
                RDBMSUtils.cleanupConnection(rs, stmt, conn);
                throw new AnalyticsException("Error in retrieving records: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void delete(int tenantId, String tableName, long timeFrom, long timeTo)
            throws AnalyticsException, AnalyticsTableNotAvailableException {
        String sql = this.getRecordDeletionQuery(tenantId, tableName);
        Connection conn = null;
        PreparedStatement stmt = null;
        try {
            conn = this.getConnection();
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1, timeFrom);
            stmt.setLong(2, timeTo);
            stmt.executeUpdate();
        } catch (SQLException e) {
            RDBMSUtils.rollbackConnection(conn);
            if (conn != null && !this.tableExists(conn, tenantId, tableName)) {
                throw new AnalyticsTableNotAvailableException(tenantId, tableName);
            } else {
                throw new AnalyticsException("Error in deleting records: " + e.getMessage(), e);
            }
        } finally {
            RDBMSUtils.cleanupConnection(null, stmt, conn);
        }
    }
        
    @Override
    public void delete(int tenantId, String tableName, 
            List<String> ids) throws AnalyticsException, AnalyticsTableNotAvailableException {
        if (ids.size() == 0) {
            return;
        }
        Connection conn = null;
        List<List<String>> idsSubLists = Lists.partition(ids, this.rdbmsQueryConfigurationEntry.getRecordBatchSize());
        try {
            conn = this.getConnection();
            for (List<String> idSubList : idsSubLists) {
                this.delete(conn, tenantId, tableName, idSubList);
            }
        } catch (SQLException e) {
            throw new AnalyticsException("Error in deleting records: " + e.getMessage(), e);
        } finally {
            RDBMSUtils.cleanupConnection(null, null, conn);
        }
    }

    @Override
    public void destroy() throws AnalyticsException {
        /* do nothing */
    }

    private void delete(Connection conn, int tenantId, String tableName, 
            List<String> ids) throws AnalyticsException, AnalyticsTableNotAvailableException {
        String sql = this.generateRecordDeletionRecordsWithIdsQuery(tenantId, tableName, ids.size());
        PreparedStatement stmt = null;
        try {
            stmt = conn.prepareStatement(sql);
            for (int i = 0; i < ids.size(); i++) {
                stmt.setString(i + 1, ids.get(i));
            }
            stmt.executeUpdate();
        } catch (SQLException e) {
            if (!this.tableExists(conn, tenantId, tableName)) {
                throw new AnalyticsTableNotAvailableException(tenantId, tableName);
            } else {
                throw new AnalyticsException("Error in deleting records: " + e.getMessage(), e);
            }
        } finally {
            RDBMSUtils.cleanupConnection(null, stmt, null);
        }
    }
    
    private String generateTargetTableName(int tenantId, String tableName) {
        return GenericUtils.generateTableUUID(tenantId, tableName);
    }
    
    private String translateQueryWithTableInfo(String query, int tenantId, String tableName) {
        if (query == null) {
            return null;
        }
        return query.replace(TABLE_NAME_PLACEHOLDER, this.generateTargetTableName(tenantId, tableName));
    }
    
    private String translateQueryWithRecordIdsInfo(String query, int recordCount) {
        return query.replace(RECORD_IDS_PLACEHOLDER, this.getDynamicSQLParams(recordCount));
    }
    
    private String getRecordRetrievalQuery(int tenantId, String tableName) {
        String query = this.getQueryConfiguration().getRecordRetrievalQuery();
        return this.translateQueryWithTableInfo(query, tenantId, tableName);
    }
    
    private String generateGetRecordRetrievalWithIdQuery(int tenantId, String tableName, int recordCount) {
        String query = this.getQueryConfiguration().getRecordRetrievalWithIdsQuery();
        query = this.translateQueryWithTableInfo(query, tenantId, tableName);
        query = this.translateQueryWithRecordIdsInfo(query, recordCount);
        return query;
    }
    
    private String generateRecordDeletionRecordsWithIdsQuery(int tenantId, String tableName, int recordCount) {
        String query = this.getQueryConfiguration().getRecordDeletionWithIdsQuery();
        query = this.translateQueryWithTableInfo(query, tenantId, tableName);
        query = this.translateQueryWithRecordIdsInfo(query, recordCount);
        return query;
    }
    
    private String getDynamicSQLParams(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i == 0) {
                builder.append("?");
            } else {
                builder.append(",?");
            }
        }
        return builder.toString();
    }
    
    private String getRecordDeletionQuery(int tenantId, String tableName) {
        String query = this.getQueryConfiguration().getRecordDeletionQuery();
        return this.translateQueryWithTableInfo(query, tenantId, tableName);
    }
    
    @Override
    public void deleteTable(int tenantId, String tableName) throws AnalyticsException {
        Connection conn = null;
        try {
            conn = this.getConnection();
            Map<String, Object[]> queries = new HashMap<String, Object[]>();
            String[] tableInitQueries = this.getRecordTableDeleteQueries(tenantId, tableName);
            queries.putAll(RDBMSUtils.generateNoParamQueryMap(tableInitQueries));
            RDBMSUtils.executeAllUpdateQueries(conn, queries);
        } catch (SQLException | AnalyticsException e) {
            if (conn == null || this.tableExists(conn, tenantId, tableName)) {
                throw new AnalyticsException("Error in deleting table: " + e.getMessage(), e);
            }            
        } finally {
            RDBMSUtils.cleanupConnection(null, null, conn);
        }
    }
        
    @Override
    public void createTable(int tenantId, String tableName) throws AnalyticsException {
        Connection conn = null;
        try {
            conn = this.getConnection();
            String[] tableInitQueries = this.getRecordTableInitQueries(tenantId, tableName);
            Map<String, Object[]> queries = RDBMSUtils.generateNoParamQueryMap(tableInitQueries);
            RDBMSUtils.executeAllUpdateQueries(conn, queries);
        } catch (SQLException | AnalyticsException e) {
            if (conn == null || !this.tableExists(conn, tenantId, tableName)) {
                throw new AnalyticsException("Error in creating table: " + e.getMessage(), e);
            }
        } finally {
            RDBMSUtils.cleanupConnection(null, null, conn);
        }
    }
    
    private String getRecordTableCheckQuery(int tenantId, String tableName) {
        String query = this.getQueryConfiguration().getRecordTableCheckQuery();
        return this.translateQueryWithTableInfo(query, tenantId, tableName);
    }
    
    private boolean tableExists(Connection conn, int tenantId, String tableName) {
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            String query = this.getRecordTableCheckQuery(tenantId, tableName);
            stmt = conn.prepareStatement(query);
            rs = stmt.executeQuery();
            return true;
        } catch (SQLException e) {
            RDBMSUtils.rollbackConnection(conn);
            return false;
        } finally {
            RDBMSUtils.cleanupConnection(rs, stmt, null);
        }
    }

    private String getRecordCountQuery(int tenantId, String tableName) {
        String query = this.getQueryConfiguration().getRecordCountQuery();
        return this.translateQueryWithTableInfo(query, tenantId, tableName);
    }
    
    private String printableTableName(int tenantId, String tableName) {
        return "[" + tenantId + ":" + tableName + "]";
    }

    @Override
    public boolean isPaginationSupported() {
        return this.rdbmsQueryConfigurationEntry.isPaginationSupported();
    }
    
    @Override
    public boolean isRecordCountSupported() {
        return this.rdbmsQueryConfigurationEntry.isRecordCountSupported();
    }

    @Override
    public long getRecordCount(int tenantId, String tableName, long timeFrom, long timeTo)
            throws AnalyticsException, AnalyticsTableNotAvailableException {
        if (this.rdbmsQueryConfigurationEntry.isRecordCountSupported()) {
            String recordCountQuery = this.getRecordCountQuery(tenantId, tableName);
            Connection conn = null;
            PreparedStatement stmt = null;
            ResultSet rs = null;
            try {
                conn = this.getConnection();
                stmt = conn.prepareStatement(recordCountQuery);
                stmt.setLong(1, timeFrom);
                stmt.setLong(2, timeTo);
                rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getLong(1);
                } else {
                    throw new AnalyticsException("Record count not available for " +
                            printableTableName(tenantId, tableName));
                }
            } catch (SQLException e) {
                if (conn != null && !this.tableExists(conn, tenantId, tableName)) {
                    throw new AnalyticsTableNotAvailableException(tenantId, tableName);
                }
                throw new AnalyticsException("Error in retrieving record count: " + e.getMessage(), e);
            } finally {
                RDBMSUtils.cleanupConnection(rs, stmt, conn);
            }
        } else {
            return -1L;
        }
    }
    
    /**
     * This class represents the RDBMS result set iterator, which will stream the result records out.
     */
    private class RDBMSResultSetIterator implements AnalyticsIterator<Record> {

        private int tenantId;
        
        private String tableName;
        
        private List<String> columns;
        
        private Connection conn;
        
        private Statement stmt;
        
        private ResultSet rs;
        
        private Record nextValue;
        
        private boolean prefetched;
        
        public RDBMSResultSetIterator(int tenantId, String tableName, List<String> columns, 
                Connection conn, Statement stmt, ResultSet rs) {
            this.tenantId = tenantId;
            this.tableName = tableName;
            this.columns = columns;
            this.conn = conn;
            this.stmt = stmt;
            this.rs = rs;
        }
        
        @Override
        public boolean hasNext() {
            if (!this.prefetched) {
                this.nextValue = this.next();
                this.prefetched = true;
            }
            return nextValue != null;
        }

        @Override
        public Record next() {
            if (this.prefetched) {
                this.prefetched = false;
                Record result = this.nextValue;
                this.nextValue = null;
                return result;
            }
            Set<String> colSet = null;
            if (this.columns != null && this.columns.size() > 0) {
                colSet = new HashSet<String>(this.columns);
            }
            try {
                if (this.rs.next()) {
                    byte[] bytes = this.rs.getBytes(3);
                    Map<String, Object> values;
                    if (bytes != null) {
                        values = GenericUtils.decodeRecordValues(bytes, colSet);
                    } else {
                        values = new HashMap<>(0);
                    }
                    return new Record(this.rs.getString(1), this.tenantId, this.tableName, values, this.rs.getLong(2));                
                } else {
                    /* end of the result set, time to clean up.. */
                    RDBMSUtils.cleanupConnection(this.rs, this.stmt, this.conn);
                    this.rs = null;
                    this.stmt = null;
                    this.conn = null;
                    return null;
                }
            } catch (Exception e) {
                RDBMSUtils.cleanupConnection(this.rs, this.stmt, this.conn);
                throw new RuntimeException(e.getMessage(), e);
            }
        }

        @Override
        public void remove() {
            /* this is a read-only iterator, nothing will be removed */
        }

        @Override
        public void finalize() {
            /* in the unlikely case, this iterator does not go to the end,
             * we have to make sure the connection is cleaned up */
            RDBMSUtils.cleanupConnection(this.rs, this.stmt, this.conn);
        }

        @Override
        public void close() throws IOException {
            RDBMSUtils.cleanupConnection(this.rs, this.stmt, this.conn);
            this.rs = null;
            this.stmt = null;
            this.conn = null;
        }
    }

    public class EmptyResultSetAnalyticsIterator implements AnalyticsIterator<Record>{

        @Override
        public void close() throws IOException {
            /* Nothing to do */
        }

        @Override
        public boolean hasNext() {
            return false;
        }

        @Override
        public Record next() {
            return null;
        }

        @Override
        public void remove() {
            /* Nothing to do */
        }
    }

    /**
     * This class exposes an array of RecordGroup objects as an Iterator.
     */
    private class RDBMSRecordIDListIterator implements AnalyticsIterator<Record> {

        private RDBMSAnalyticsRecordStore reader;

        private RecordGroup[] rgs;

        private Iterator<Record> itr;

        private int index = -1;

        public RDBMSRecordIDListIterator(RDBMSAnalyticsRecordStore reader, RecordGroup[] rgs)
                throws AnalyticsException {
            this.reader = reader;
            this.rgs = rgs;
        }

        @Override
        public boolean hasNext() {
            boolean result;
            if (this.itr == null) {
                result = false;
            } else {
                result = this.itr.hasNext();
            }
            if (result) {
                return true;
            } else {
                if (rgs.length > this.index + 1) {
                    try {
                        this.index++;
                        RDBMSIDsRecordGroup recordIdGroup = (RDBMSIDsRecordGroup) (rgs[index]);
                        this.itr = this.reader.getRecords(recordIdGroup.getTenantId(), recordIdGroup.getTableName(),
                                recordIdGroup.getColumns(), recordIdGroup.getIds());
                    } catch (AnalyticsException e) {
                        throw new IllegalStateException("Error in traversing record group: " + e.getMessage(), e);
                    }
                    return this.hasNext();
                } else {
                    return false;
                }
            }
        }

        @Override
        public Record next() {
            if (this.hasNext()) {
                return this.itr.next();
            } else {
                return null;
            }
        }

        @Override
        public void remove() {
            /* ignored */
        }

        @Override
        public void close() throws IOException {
            /* ignored */
        }
    }


}
