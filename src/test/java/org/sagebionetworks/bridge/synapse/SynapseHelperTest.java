package org.sagebionetworks.bridge.synapse;

import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import org.mockito.ArgumentCaptor;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseResultNotReadyException;
import org.sagebionetworks.repo.model.AccessControlList;
import org.sagebionetworks.repo.model.ResourceAccess;
import org.sagebionetworks.repo.model.file.FileHandle;
import org.sagebionetworks.repo.model.file.S3FileHandle;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.CsvTableDescriptor;
import org.sagebionetworks.repo.model.table.TableEntity;
import org.sagebionetworks.repo.model.table.TableUpdateRequest;
import org.sagebionetworks.repo.model.table.TableUpdateResponse;
import org.sagebionetworks.repo.model.table.UploadToTableResult;
import org.sagebionetworks.repo.model.util.ModelConstants;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import org.sagebionetworks.bridge.exceptions.BridgeSynapseNonRetryableException;

@SuppressWarnings("unchecked")
public class SynapseHelperTest {
    @Test
    public void createTableWithColumnsAndAcls() throws Exception {
        // Set up inputs. This table will have two columns. We pass this straight through to the create column call,
        // and we never look inside, so don't bother actually instantiating the columns.
        List<ColumnModel> columnList = ImmutableList.of(new ColumnModel(), new ColumnModel());

        // Spy SynapseHelper. This way, we can test the logic in SynapseHelper without being tightly coupled to the
        // implementations of create column, create table, and create ACLs.
        SynapseHelper synapseHelper = spy(new SynapseHelper());

        // mock create column call - We only care about the IDs, so don't bother instantiating the rest.
        ColumnModel createdFooColumn = new ColumnModel();
        createdFooColumn.setId("foo-col-id");

        ColumnModel createdBarColumn = new ColumnModel();
        createdBarColumn.setId("bar-col-id");

        List<ColumnModel> createdColumnList = ImmutableList.of(createdFooColumn, createdBarColumn);

        doReturn(createdColumnList).when(synapseHelper).createColumnModelsWithRetry(columnList);

        // mock create table call - We only care about the table ID, so don't bother instantiating the rest.
        TableEntity createdTable = new TableEntity();
        createdTable.setId("test-table");

        ArgumentCaptor<TableEntity> tableCaptor = ArgumentCaptor.forClass(TableEntity.class);
        doReturn(createdTable).when(synapseHelper).createTableWithRetry(tableCaptor.capture());

        // Mock create ACL call. Even though we don't care about the return value, we have to do it, otherwise it'll
        // call through to the real method. Likewise, for the result, just return a dummy object. We never look at it
        // anyway.
        ArgumentCaptor<AccessControlList> aclCaptor = ArgumentCaptor.forClass(AccessControlList.class);
        doReturn(new AccessControlList()).when(synapseHelper).createAclWithRetry(aclCaptor.capture());

        // execute and validate
        Set<Long> readOnlyPrincipalSet = ImmutableSet.of(1111L, 2222L);
        Set<Long> adminPrincipalSet = ImmutableSet.of(3333L, 4444L);
        String retVal = synapseHelper.createTableWithColumnsAndAcls(columnList, readOnlyPrincipalSet, adminPrincipalSet,
                "test-project", "My Table");
        assertEquals(retVal, "test-table");

        // validate tableCaptor
        TableEntity table = tableCaptor.getValue();
        assertEquals(table.getName(), "My Table");
        assertEquals(table.getParentId(), "test-project");
        assertEquals(table.getColumnIds(), ImmutableList.of("foo-col-id", "bar-col-id"));

        // validate aclCaptor
        AccessControlList acl = aclCaptor.getValue();
        assertEquals(acl.getId(), "test-table");

        Set<ResourceAccess> resourceAccessSet = acl.getResourceAccess();
        assertEquals(resourceAccessSet.size(), 4);
        Map<Long, ResourceAccess> resourceAccessByPrincipalId = Maps.uniqueIndex(resourceAccessSet,
                ResourceAccess::getPrincipalId);
        assertEquals(resourceAccessByPrincipalId.size(), 4);

        assertEquals(resourceAccessByPrincipalId.get(1111L).getAccessType(), SynapseHelper.ACCESS_TYPE_READ);
        assertEquals(resourceAccessByPrincipalId.get(2222L).getAccessType(), SynapseHelper.ACCESS_TYPE_READ);
        assertEquals(resourceAccessByPrincipalId.get(3333L).getAccessType(),
                ModelConstants.ENTITY_ADMIN_ACCESS_PERMISSIONS);
        assertEquals(resourceAccessByPrincipalId.get(4444L).getAccessType(),
                ModelConstants.ENTITY_ADMIN_ACCESS_PERMISSIONS);
    }

    @Test
    public void isCompatibleColumnTypeChanges() throws Exception {
        ColumnModel intColumn = new ColumnModel();
        intColumn.setName("my-column");
        intColumn.setColumnType(ColumnType.INTEGER);

        ColumnModel floatColumn = new ColumnModel();
        floatColumn.setName("my-column");
        floatColumn.setColumnType(ColumnType.DOUBLE);

        assertTrue(SynapseHelper.isCompatibleColumn(intColumn, floatColumn));
        assertFalse(SynapseHelper.isCompatibleColumn(floatColumn, intColumn));
    }

    @DataProvider
    public Object[][] isCompatibleMaxLengthStringToStringDataProvider() {
        // { oldMaxLength, newMaxLength, expected }
        return new Object[][] {
                { 10, 20, true },
                { 20, 10, false },
                { 15, 15, true },
        };
    }

    @Test(dataProvider = "isCompatibleMaxLengthStringToStringDataProvider")
    public void isCompatibleMaxLengthStringToString(long oldMaxLength, long newMaxLength, boolean expected)
            throws Exception {
        ColumnModel oldColumn = new ColumnModel();
        oldColumn.setName("my-string");
        oldColumn.setColumnType(ColumnType.STRING);
        oldColumn.setMaximumSize(oldMaxLength);

        ColumnModel newColumn = new ColumnModel();
        newColumn.setName("my-string");
        newColumn.setColumnType(ColumnType.STRING);
        newColumn.setMaximumSize(newMaxLength);

        assertEquals(SynapseHelper.isCompatibleColumn(oldColumn, newColumn), expected);
    }

    @DataProvider
    public Object[][] isCompatibleMaxLengthWithNonStringsDataProvider() {
        // { oldType, newType, newMaxLength, expected }
        return new Object[][] {
                { ColumnType.INTEGER, ColumnType.INTEGER, null, true },
                { ColumnType.DOUBLE, ColumnType.STRING, 21L, false },
                { ColumnType.DOUBLE, ColumnType.STRING, 23L, true },
                { ColumnType.INTEGER, ColumnType.STRING, 19L, false },
                { ColumnType.INTEGER, ColumnType.STRING, 21L, true },
        };
    }

    @Test(dataProvider = "isCompatibleMaxLengthWithNonStringsDataProvider")
    public void isCompatibleMaxLengthWithNonStrings(ColumnType oldType, ColumnType newType, Long newMaxLength,
            boolean expected) throws Exception {
        ColumnModel oldColumn = new ColumnModel();
        oldColumn.setName("my-column");
        oldColumn.setColumnType(oldType);
        oldColumn.setMaximumSize(null);

        ColumnModel newColumn = new ColumnModel();
        newColumn.setName("my-column");
        newColumn.setColumnType(newType);
        newColumn.setMaximumSize(newMaxLength);

        assertEquals(SynapseHelper.isCompatibleColumn(oldColumn, newColumn), expected);
    }

    // branch coverage
    @Test(expectedExceptions = BridgeSynapseNonRetryableException.class, expectedExceptionsMessageRegExp =
            "old column my-string has type STRING and no max length")
    public void isCompatibleOldStringWithNoMaxLength() throws Exception {
        ColumnModel oldColumn = new ColumnModel();
        oldColumn.setName("my-string");
        oldColumn.setColumnType(ColumnType.STRING);
        oldColumn.setMaximumSize(null);

        ColumnModel newColumn = new ColumnModel();
        newColumn.setName("my-string");
        newColumn.setColumnType(ColumnType.STRING);
        newColumn.setMaximumSize(42L);

        SynapseHelper.isCompatibleColumn(oldColumn, newColumn);
    }

    // branch coverage
    @Test(expectedExceptions = BridgeSynapseNonRetryableException.class, expectedExceptionsMessageRegExp =
            "new column my-string has type STRING and no max length")
    public void isCompatibleNewStringWithNoMaxLength() throws Exception {
        ColumnModel oldColumn = new ColumnModel();
        oldColumn.setName("my-string");
        oldColumn.setColumnType(ColumnType.STRING);
        oldColumn.setMaximumSize(42L);

        ColumnModel newColumn = new ColumnModel();
        newColumn.setName("my-string");
        newColumn.setColumnType(ColumnType.STRING);
        newColumn.setMaximumSize(null);

        SynapseHelper.isCompatibleColumn(oldColumn, newColumn);
    }

    @Test
    public void isCompatibleColumnNameChange() throws Exception {
        ColumnModel fooColumn = new ColumnModel();
        fooColumn.setName("foo");
        fooColumn.setColumnType(ColumnType.INTEGER);

        ColumnModel barColumn = new ColumnModel();
        barColumn.setName("bar");
        barColumn.setColumnType(ColumnType.INTEGER);

        assertFalse(SynapseHelper.isCompatibleColumn(fooColumn, barColumn));
    }

    // Most of these are retry wrappers, but we should test them anyway for branch coverage.

    @Test
    public void createAcl() throws Exception {
        // mock Synapse Client - Unclear whether Synapse client just passes back the input ACL or if it creates a new
        // one. Regardless, don't depend on this implementation. Just return a separate one for tests.
        SynapseClient mockSynapseClient = mock(SynapseClient.class);
        AccessControlList inputAcl = new AccessControlList();
        AccessControlList outputAcl = new AccessControlList();
        when(mockSynapseClient.createACL(inputAcl)).thenReturn(outputAcl);

        SynapseHelper synapseHelper = new SynapseHelper();
        synapseHelper.setSynapseClient(mockSynapseClient);

        // execute and validate
        AccessControlList retVal = synapseHelper.createAclWithRetry(inputAcl);
        assertSame(retVal, outputAcl);
    }

    @Test
    public void createColumnModels() throws Exception {
        // mock Synapse Client - Similarly, return a new output list
        SynapseClient mockSynapseClient = mock(SynapseClient.class);
        List<ColumnModel> inputColumnModelList = ImmutableList.of(new ColumnModel());
        List<ColumnModel> outputColumnModelList = ImmutableList.of(new ColumnModel());
        when(mockSynapseClient.createColumnModels(inputColumnModelList)).thenReturn(outputColumnModelList);

        SynapseHelper synapseHelper = new SynapseHelper();
        synapseHelper.setSynapseClient(mockSynapseClient);

        // execute and validate
        List<ColumnModel> retVal = synapseHelper.createColumnModelsWithRetry(inputColumnModelList);
        assertSame(retVal, outputColumnModelList);
    }

    @Test
    public void createFileHandle() throws Exception {
        // mock Synapse Client
        SynapseClient mockSynapseClient = mock(SynapseClient.class);

        File mockFile = mock(File.class);
        S3FileHandle mockFileHandle = mock(S3FileHandle.class);
        when(mockSynapseClient.multipartUpload(mockFile, null, null, null)).thenReturn(mockFileHandle);

        SynapseHelper synapseHelper = new SynapseHelper();
        synapseHelper.setSynapseClient(mockSynapseClient);

        // execute and validate
        FileHandle retVal = synapseHelper.createFileHandleWithRetry(mockFile);
        assertSame(retVal, mockFileHandle);
    }

    @Test
    public void createTable() throws Exception {
        // mock Synapse Client - Similarly, return a new output table
        SynapseClient mockSynapseClient = mock(SynapseClient.class);
        TableEntity inputTable = new TableEntity();
        TableEntity outputTable = new TableEntity();
        when(mockSynapseClient.createEntity(inputTable)).thenReturn(outputTable);

        SynapseHelper synapseHelper = new SynapseHelper();
        synapseHelper.setSynapseClient(mockSynapseClient);

        // execute and validate
        TableEntity retVal = synapseHelper.createTableWithRetry(inputTable);
        assertSame(retVal, outputTable);
    }

    @Test
    public void getColumnModelsForTable() throws Exception {
        // mock Synapse Client
        SynapseClient mockSynapseClient = mock(SynapseClient.class);
        List<ColumnModel> outputColumnModelList = ImmutableList.of(new ColumnModel());
        when(mockSynapseClient.getColumnModelsForTableEntity("table-id")).thenReturn(outputColumnModelList);

        SynapseHelper synapseHelper = new SynapseHelper();
        synapseHelper.setSynapseClient(mockSynapseClient);

        // execute and validate
        List<ColumnModel> retVal = synapseHelper.getColumnModelsForTableWithRetry("table-id");
        assertSame(retVal, outputColumnModelList);
    }

    @Test
    public void getTable() throws Exception {
        // mock Synapse Client
        SynapseClient mockSynapseClient = mock(SynapseClient.class);
        TableEntity tableEntity = new TableEntity();
        when(mockSynapseClient.getEntity("table-id", TableEntity.class)).thenReturn(tableEntity);

        SynapseHelper synapseHelper = new SynapseHelper();
        synapseHelper.setSynapseClient(mockSynapseClient);

        // execute and validate
        TableEntity retVal = synapseHelper.getTableWithRetry("table-id");
        assertSame(retVal, tableEntity);
    }

    @Test
    public void startTableTransaction() throws Exception {
        // mock Synapse Client
        List<TableUpdateRequest> dummyChangeList = ImmutableList.of();
        SynapseClient mockSynapseClient = mock(SynapseClient.class);
        when(mockSynapseClient.startTableTransactionJob(same(dummyChangeList), eq("table-id"))).thenReturn(
                "job-token");

        SynapseHelper synapseHelper = new SynapseHelper();
        synapseHelper.setSynapseClient(mockSynapseClient);

        // execute and validate
        String retVal = synapseHelper.startTableTransactionWithRetry(dummyChangeList, "table-id");
        assertEquals(retVal, "job-token");
    }

    @Test
    public void getTableTransactionResult() throws Exception {
        // mock Synapse Client
        List<TableUpdateResponse> dummyResponseList = ImmutableList.of();
        SynapseClient mockSynapseClient = mock(SynapseClient.class);
        when(mockSynapseClient.getTableTransactionJobResults("job-token", "table-id")).thenReturn(dummyResponseList);

        SynapseHelper synapseHelper = new SynapseHelper();
        synapseHelper.setSynapseClient(mockSynapseClient);

        // execute and validate
        List<TableUpdateResponse> retVal = synapseHelper.getTableTransactionResultWithRetry("job-token", "table-id");
        assertSame(retVal, dummyResponseList);
    }

    @Test
    public void getTableTransactionResultNotReady() throws Exception {
        // mock Synapse Client
        SynapseClient mockSynapseClient = mock(SynapseClient.class);
        when(mockSynapseClient.getTableTransactionJobResults("job-token", "table-id")).thenThrow(
                SynapseResultNotReadyException.class);

        SynapseHelper synapseHelper = new SynapseHelper();
        synapseHelper.setSynapseClient(mockSynapseClient);

        // execute and validate
        List<TableUpdateResponse> retVal = synapseHelper.getTableTransactionResultWithRetry("job-token", "table-id");
        assertNull(retVal);
    }

    @Test
    public void uploadTsvStart() throws Exception {
        // mock Synapse Client
        SynapseClient mockSynapseClient = mock(SynapseClient.class);
        CsvTableDescriptor inputTableDesc = new CsvTableDescriptor();
        when(mockSynapseClient.uploadCsvToTableAsyncStart("table-id", "file-handle-id", null, null, inputTableDesc,
                null)).thenReturn("job-token");

        SynapseHelper synapseHelper = new SynapseHelper();
        synapseHelper.setSynapseClient(mockSynapseClient);

        // execute and validate
        String retVal = synapseHelper.uploadTsvStartWithRetry("table-id", "file-handle-id", inputTableDesc);
        assertEquals(retVal, "job-token");
    }

    @Test
    public void uploadTsvStatus() throws Exception {
        // mock SynapseClient
        SynapseClient mockSynapseClient = mock(SynapseClient.class);
        UploadToTableResult result = new UploadToTableResult();
        when(mockSynapseClient.uploadCsvToTableAsyncGet("job-token", "table-id")).thenReturn(result);

        SynapseHelper synapseHelper = new SynapseHelper();
        synapseHelper.setSynapseClient(mockSynapseClient);

        // execute and validate
        UploadToTableResult retVal = synapseHelper.getUploadTsvStatus("job-token", "table-id");
        assertSame(retVal, result);
    }

    @Test
    public void uploadTsvStatusNotReady() throws Exception {
        // mock SynapseClient
        SynapseClient mockSynapseClient = mock(SynapseClient.class);
        when(mockSynapseClient.uploadCsvToTableAsyncGet("job-token", "table-id"))
                .thenThrow(SynapseResultNotReadyException.class);

        SynapseHelper synapseHelper = new SynapseHelper();
        synapseHelper.setSynapseClient(mockSynapseClient);

        // execute and validate
        UploadToTableResult retVal = synapseHelper.getUploadTsvStatus("job-token", "table-id");
        assertNull(retVal);
    }
}
