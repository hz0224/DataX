package com.alibaba.datax.plugin.reader.hdfsreader;

import com.alibaba.datax.common.element.*;
import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.common.plugin.RecordSender;
import com.alibaba.datax.common.plugin.TaskPluginCollector;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.plugin.unstructuredstorage.reader.ColumnEntry;
import com.alibaba.datax.plugin.unstructuredstorage.reader.UnstructuredStorageReaderErrorCode;
import com.alibaba.datax.plugin.unstructuredstorage.reader.UnstructuredStorageReaderUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.io.RCFile;
import org.apache.hadoop.hive.ql.io.RCFileRecordReader;
import org.apache.hadoop.hive.ql.io.orc.OrcFile;
import org.apache.hadoop.hive.ql.io.orc.OrcInputFormat;
import org.apache.hadoop.hive.ql.io.orc.OrcSerde;
import org.apache.hadoop.hive.ql.io.orc.Reader;
import org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat;
import org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe;
import org.apache.hadoop.hive.serde2.columnar.BytesRefArrayWritable;
import org.apache.hadoop.hive.serde2.columnar.BytesRefWritable;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.io.*;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.example.GroupReadSupport;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Created by mingya.wmy on 2015/8/12.
 */
public class DFSUtil {
    private static final Logger LOG = LoggerFactory.getLogger(HdfsReader.Job.class);

    private org.apache.hadoop.conf.Configuration hadoopConf = null;
    private String specifiedFileType = null;
    private Boolean haveKerberos = false;
    private String kerberosKeytabFilePath;
    private String kerberosPrincipal;


    private static final int DIRECTORY_SIZE_GUESS = 16 * 1024;

    public static final String HDFS_DEFAULTFS_KEY = "fs.defaultFS";
    public static final String HADOOP_SECURITY_AUTHENTICATION_KEY = "hadoop.security.authentication";


    public DFSUtil(Configuration taskConfig) {
        hadoopConf = new org.apache.hadoop.conf.Configuration();
        //io.file.buffer.size ????????????
        //http://blog.csdn.net/yangjl38/article/details/7583374
        Configuration hadoopSiteParams = taskConfig.getConfiguration(Key.HADOOP_CONFIG);
        JSONObject hadoopSiteParamsAsJsonObject = JSON.parseObject(taskConfig.getString(Key.HADOOP_CONFIG));
        if (null != hadoopSiteParams) {
            Set<String> paramKeys = hadoopSiteParams.getKeys();
            for (String each : paramKeys) {
                hadoopConf.set(each, hadoopSiteParamsAsJsonObject.getString(each));
            }
        }
        hadoopConf.set(HDFS_DEFAULTFS_KEY, taskConfig.getString(Key.DEFAULT_FS));

        //?????????Kerberos??????
        this.haveKerberos = taskConfig.getBool(Key.HAVE_KERBEROS, false);
        if (haveKerberos) {
            this.kerberosKeytabFilePath = taskConfig.getString(Key.KERBEROS_KEYTAB_FILE_PATH);
            this.kerberosPrincipal = taskConfig.getString(Key.KERBEROS_PRINCIPAL);
            this.hadoopConf.set(HADOOP_SECURITY_AUTHENTICATION_KEY, "kerberos");
        }
        this.kerberosAuthentication(this.kerberosPrincipal, this.kerberosKeytabFilePath);

        LOG.info(String.format("hadoopConfig details:%s", JSON.toJSONString(this.hadoopConf)));
    }

    private void kerberosAuthentication(String kerberosPrincipal, String kerberosKeytabFilePath) {
        if (haveKerberos && StringUtils.isNotBlank(this.kerberosPrincipal) && StringUtils.isNotBlank(this.kerberosKeytabFilePath)) {
            UserGroupInformation.setConfiguration(this.hadoopConf);
            try {
                UserGroupInformation.loginUserFromKeytab(kerberosPrincipal, kerberosKeytabFilePath);
            } catch (Exception e) {
                String message = String.format("kerberos????????????,?????????kerberosKeytabFilePath[%s]???kerberosPrincipal[%s]????????????",
                        kerberosKeytabFilePath, kerberosPrincipal);
                throw DataXException.asDataXException(HdfsReaderErrorCode.KERBEROS_LOGIN_ERROR, message, e);
            }
        }
    }

    /**
     * ?????????????????????????????????????????????????????????????????????
     *
     * @param srcPaths          ????????????
     * @param specifiedFileType ??????????????????
     */
    public HashSet<String> getAllFiles(List<String> srcPaths, String specifiedFileType) {

        this.specifiedFileType = specifiedFileType;

        if (!srcPaths.isEmpty()) {
            for (String eachPath : srcPaths) {
                LOG.info(String.format("get HDFS all files in path = [%s]", eachPath));
                getHDFSAllFiles(eachPath);
            }
        }
        return sourceHDFSAllFilesList;
    }

    private HashSet<String> sourceHDFSAllFilesList = new HashSet<String>();

    public HashSet<String> getHDFSAllFiles(String hdfsPath) {

        try {
            FileSystem hdfs = FileSystem.get(hadoopConf);
            //??????hdfsPath????????????????????????
            if (hdfsPath.contains("*") || hdfsPath.contains("?")) {
                Path path = new Path(hdfsPath);
                FileStatus stats[] = hdfs.globStatus(path);
                for (FileStatus f : stats) {
                    if (f.isFile()) {
                        if (f.getLen() == 0) {
                            String message = String.format("??????[%s]?????????0??????????????????????????????", hdfsPath);
                            LOG.warn(message);
                        } else {
                            addSourceFileByType(f.getPath().toString());
                        }
                    } else if (f.isDirectory()) {
                        getHDFSAllFilesNORegex(f.getPath().toString(), hdfs);
                    }
                }
            } else {
                getHDFSAllFilesNORegex(hdfsPath, hdfs);
            }

            return sourceHDFSAllFilesList;

        } catch (IOException e) {
            String message = String.format("??????????????????[%s]??????????????????,????????????????????????fs.defaultFS, path?????????????????????" +
                    "????????????????????????????????????????????????", hdfsPath);
            LOG.error(message);
            throw DataXException.asDataXException(HdfsReaderErrorCode.PATH_CONFIG_ERROR, e);
        }
    }

    private HashSet<String> getHDFSAllFilesNORegex(String path, FileSystem hdfs) throws IOException {

        // ????????????????????????????????????
        Path listFiles = new Path(path);

        // If the network disconnected, this method will retry 45 times
        // each time the retry interval for 20 seconds
        // ??????????????????????????????????????????????????????????????????
        FileStatus stats[] = hdfs.listStatus(listFiles);

        for (FileStatus f : stats) {
            // ??????????????????????????????????????????????????????
            if (f.isDirectory()) {
                LOG.info(String.format("[%s] ?????????, ?????????????????????????????????", f.getPath().toString()));
                getHDFSAllFilesNORegex(f.getPath().toString(), hdfs);
            } else if (f.isFile()) {

                addSourceFileByType(f  .getPath().toString());
            } else {
                String message = String.format("?????????[%s]??????????????????????????????????????????????????????????????????",
                        f.getPath().toString());
                LOG.info(message);
            }
        }
        return sourceHDFSAllFilesList;
    }

    // ???????????????????????????????????????????????????????????????????????????sourceHDFSAllFilesList
    private void addSourceFileByType(String filePath) {
        // ??????file???????????????????????????fileType  ??????????????????
        boolean isMatchedFileType = checkHdfsFileType(filePath, this.specifiedFileType);

        if (isMatchedFileType) {
            LOG.info(String.format("[%s]???[%s]???????????????, ??????????????????source files??????", filePath, this.specifiedFileType));
            sourceHDFSAllFilesList.add(filePath);
        } else {
            String message = String.format("??????[%s]???????????????????????????fileType??????????????????" +
                            "????????????????????????????????????????????????????????????[%s]"
                    , filePath, this.specifiedFileType);
            LOG.error(message);
            throw DataXException.asDataXException(
                    HdfsReaderErrorCode.FILE_TYPE_UNSUPPORT, message);
        }
    }

    public InputStream getInputStream(String filepath) {
        InputStream inputStream;
        Path path = new Path(filepath);
        try {
            FileSystem fs = FileSystem.get(hadoopConf);
            //If the network disconnected, this method will retry 45 times
            //each time the retry interval for 20 seconds
            inputStream = fs.open(path);
            return inputStream;
        } catch (IOException e) {
            String message = String.format("???????????? : [%s] ?????????,??????????????????[%s]???????????????????????????????????????", filepath, filepath);
            throw DataXException.asDataXException(HdfsReaderErrorCode.READ_FILE_ERROR, message, e);
        }
    }

    public void sequenceFileStartRead(String sourceSequenceFilePath, Configuration readerSliceConfig,
                                      RecordSender recordSender, TaskPluginCollector taskPluginCollector) {
        LOG.info(String.format("Start Read sequence file [%s].", sourceSequenceFilePath));

        Path seqFilePath = new Path(sourceSequenceFilePath);
        SequenceFile.Reader reader = null;
        try {
            //??????SequenceFile.Reader??????
            reader = new SequenceFile.Reader(this.hadoopConf,
                    SequenceFile.Reader.file(seqFilePath));
            //??????key ??? value
            Writable key = (Writable) ReflectionUtils.newInstance(reader.getKeyClass(), this.hadoopConf);
            Text value = new Text();
            while (reader.next(key, value)) {
                if (StringUtils.isNotBlank(value.toString())) {
                    UnstructuredStorageReaderUtil.transportOneRecord(recordSender,
                            readerSliceConfig, taskPluginCollector, value.toString());
                }
            }
        } catch (Exception e) {
            String message = String.format("SequenceFile.Reader????????????[%s]?????????", sourceSequenceFilePath);
            LOG.error(message);
            throw DataXException.asDataXException(HdfsReaderErrorCode.READ_SEQUENCEFILE_ERROR, message, e);
        } finally {
            IOUtils.closeStream(reader);
            LOG.info("Finally, Close stream SequenceFile.Reader.");
        }

    }

    public void rcFileStartRead(String sourceRcFilePath, Configuration readerSliceConfig,
                                RecordSender recordSender, TaskPluginCollector taskPluginCollector) {
        LOG.info(String.format("Start Read rcfile [%s].", sourceRcFilePath));
        List<ColumnEntry> column = UnstructuredStorageReaderUtil
                .getListColumnEntry(readerSliceConfig, com.alibaba.datax.plugin.unstructuredstorage.reader.Key.COLUMN);
        // warn: no default value '\N'
        String nullFormat = readerSliceConfig.getString(com.alibaba.datax.plugin.unstructuredstorage.reader.Key.NULL_FORMAT);

        Path rcFilePath = new Path(sourceRcFilePath);
        FileSystem fs = null;
        RCFileRecordReader recordReader = null;
        try {
            fs = FileSystem.get(rcFilePath.toUri(), hadoopConf);
            long fileLen = fs.getFileStatus(rcFilePath).getLen();
            FileSplit split = new FileSplit(rcFilePath, 0, fileLen, (String[]) null);
            recordReader = new RCFileRecordReader(hadoopConf, split);
            LongWritable key = new LongWritable();
            BytesRefArrayWritable value = new BytesRefArrayWritable();
            Text txt = new Text();
            while (recordReader.next(key, value)) {
                String[] sourceLine = new String[value.size()];
                txt.clear();
                for (int i = 0; i < value.size(); i++) {
                    BytesRefWritable v = value.get(i);
                    txt.set(v.getData(), v.getStart(), v.getLength());
                    sourceLine[i] = txt.toString();
                }
                UnstructuredStorageReaderUtil.transportOneRecord(recordSender,
                        column, sourceLine, nullFormat, taskPluginCollector);
            }

        } catch (IOException e) {
            String message = String.format("????????????[%s]?????????", sourceRcFilePath);
            LOG.error(message);
            throw DataXException.asDataXException(HdfsReaderErrorCode.READ_RCFILE_ERROR, message, e);
        } finally {
            try {
                if (recordReader != null) {
                    recordReader.close();
                    LOG.info("Finally, Close RCFileRecordReader.");
                }
            } catch (IOException e) {
                LOG.warn(String.format("finally: ??????RCFileRecordReader??????, %s", e.getMessage()));
            }
        }

    }

    public void orcFileStartRead(String sourceOrcFilePath, Configuration readerSliceConfig,
                                 RecordSender recordSender, TaskPluginCollector taskPluginCollector) {
        LOG.info(String.format("Start Read orcfile [%s].", sourceOrcFilePath));
        List<ColumnEntry> column = UnstructuredStorageReaderUtil
                .getListColumnEntry(readerSliceConfig, com.alibaba.datax.plugin.unstructuredstorage.reader.Key.COLUMN);
        String nullFormat = readerSliceConfig.getString(com.alibaba.datax.plugin.unstructuredstorage.reader.Key.NULL_FORMAT);
        StringBuilder allColumns = new StringBuilder();
        StringBuilder allColumnTypes = new StringBuilder();
        boolean isReadAllColumns = false;
        int columnIndexMax = -1;
        // ???????????????????????????
        if (null == column || column.size() == 0) {
            int allColumnsCount = getAllColumnsCount(sourceOrcFilePath);
            columnIndexMax = allColumnsCount - 1;
            isReadAllColumns = true;
        } else {
            columnIndexMax = getMaxIndex(column);
        }
        for (int i = 0; i <= columnIndexMax; i++) {
            allColumns.append("col");
            allColumnTypes.append("string");
            if (i != columnIndexMax) {
                allColumns.append(",");
                allColumnTypes.append(":");
            }
        }
        if (columnIndexMax >= 0) {
            JobConf conf = new JobConf(hadoopConf);
            Path orcFilePath = new Path(sourceOrcFilePath);
            Properties p = new Properties();
            p.setProperty("columns", allColumns.toString());
            p.setProperty("columns.types", allColumnTypes.toString());
            try {
                OrcSerde serde = new OrcSerde();
                serde.initialize(conf, p);
                StructObjectInspector inspector = (StructObjectInspector) serde.getObjectInspector();
                InputFormat<?, ?> in = new OrcInputFormat();
                FileInputFormat.setInputPaths(conf, orcFilePath.toString());

                //If the network disconnected, will retry 45 times, each time the retry interval for 20 seconds
                //Each file as a split
                //TODO multy threads
                InputSplit[] splits = in.getSplits(conf,Integer.parseInt(Key.SPLITS));
                for (InputSplit split : splits){
                    RecordReader reader = in.getRecordReader(split, conf, Reporter.NULL);
                    Object key = reader.createKey();
                    Object value = reader.createValue();
                    // ???????????????
                    List<? extends StructField> fields = inspector.getAllStructFieldRefs();

                    List<Object> recordFields;
                    while (reader.next(key, value)) {
                        recordFields = new ArrayList<Object>();

                        for (int i = 0; i <= columnIndexMax; i++) {
                            Object field = inspector.getStructFieldData(value, fields.get(i));
                            recordFields.add(field);
                        }
                        transportOneRecord(column, recordFields, recordSender,
                                taskPluginCollector, isReadAllColumns, nullFormat);
                    }
                    reader.close();
                }

            } catch (Exception e) {
                String message = String.format("???orcfile????????????[%s]?????????????????????????????????????????????????????????"
                        , sourceOrcFilePath);
                LOG.error(message);
                throw DataXException.asDataXException(HdfsReaderErrorCode.READ_FILE_ERROR, message);
            }
        } else {
            String message = String.format("??????????????????????????????????????????columnIndexMax ??????0,column:%s", JSON.toJSONString(column));
            throw DataXException.asDataXException(HdfsReaderErrorCode.BAD_CONFIG_VALUE, message);
        }
    }

    public void parquetFileStartRead(String sourceOrcFilePath, Configuration readerSliceConfig,
                                     RecordSender recordSender, TaskPluginCollector taskPluginCollector) {
        LOG.info(String.format("Start Read parquetfile [%s].", sourceOrcFilePath));
        List<ColumnEntry> column = UnstructuredStorageReaderUtil
                .getListColumnEntry(readerSliceConfig, com.alibaba.datax.plugin.unstructuredstorage.reader.Key.COLUMN);
        String nullFormat = readerSliceConfig.getString(com.alibaba.datax.plugin.unstructuredstorage.reader.Key.NULL_FORMAT);
        StringBuilder allColumns = new StringBuilder();
        StringBuilder allColumnTypes = new StringBuilder();
        boolean isReadAllColumns = false;
        int columnIndexMax = -1;
        // ???????????????????????????
        if (null == column || column.size() == 0) {
            int allColumnsCount = getAllColumnsCount_parquet(sourceOrcFilePath);
            columnIndexMax = allColumnsCount - 1;
            isReadAllColumns = true;
        } else {
            columnIndexMax = getMaxIndex(column);
        }
        for (int i = 0; i <= columnIndexMax; i++) {
            allColumns.append("col");
            allColumnTypes.append("string");
            if (i != columnIndexMax) {
                allColumns.append(",");
                allColumnTypes.append(":");
            }
        }
        if (columnIndexMax >= 0) {
            JobConf conf = new JobConf(hadoopConf);
            Path parquetFilePath = new Path(sourceOrcFilePath);
            Properties p = new Properties();
            p.setProperty("columns", allColumns.toString());
            p.setProperty("columns.types", allColumnTypes.toString());
            try {
                ParquetHiveSerDe serde = new ParquetHiveSerDe();
                serde.initialize(conf, p);
                StructObjectInspector inspector = (StructObjectInspector) serde.getObjectInspector();
                InputFormat<?, ?> in = new MapredParquetInputFormat();
                FileInputFormat.setInputPaths(conf, parquetFilePath.toString());
                String framework = conf.get("mapreduce.framework.name", "yarn");
                String n = framework.equals("classic") ? conf.get("mapreduce.jobtracker.kerberos.principal") : conf.get("yarn.resourcemanager.principal");
                LOG.info("mapreduce.framework.name is {} kerbros result is {}",framework,n);
                //If the network disconnected, will retry 45 times, each time the retry interval for 20 seconds
                //Each file as a split
                //TODO multy threads
                InputSplit[] splits = in.getSplits(conf, 5);
                for(InputSplit split : splits){
                    RecordReader reader = in.getRecordReader(split, conf, Reporter.NULL);
                    Object key = reader.createKey();
                    Object value = reader.createValue();
                    // ???????????????
                    List<? extends StructField> fields = inspector.getAllStructFieldRefs();

                    List<Object> recordFields;
                    while (reader.next(key, value)) {
                        recordFields = new ArrayList<Object>();

                        for (int i = 0; i <= columnIndexMax; i++) {
                            Object field = inspector.getStructFieldData(value, fields.get(i));
                            recordFields.add(field);
                        }
                        transportOneRecord(column, recordFields, recordSender, taskPluginCollector, isReadAllColumns, nullFormat);
                    }
                    reader.close();
                }

            } catch (Exception e) {
                e.printStackTrace();
                String message = String.format("???parquetfile????????????[%s]?????????????????????????????????????????????????????????"
                        , sourceOrcFilePath);
                LOG.error(message);
                throw DataXException.asDataXException(HdfsReaderErrorCode.READ_FILE_ERROR, message);
            }
        } else {
            String message = String.format("??????????????????????????????????????????columnIndexMax ??????0,column:%s", JSON.toJSONString(column));
            throw DataXException.asDataXException(HdfsReaderErrorCode.BAD_CONFIG_VALUE, message);
        }
    }
    private Record transportOneRecord(List<ColumnEntry> columnConfigs, List<Object> recordFields
            , RecordSender recordSender, TaskPluginCollector taskPluginCollector, boolean isReadAllColumns, String nullFormat) {
        Record record = recordSender.createRecord();
        Column columnGenerated;
        try {
            if (isReadAllColumns) {
                // ??????????????????????????????String?????????column
                for (Object recordField : recordFields) {
                    String columnValue = null;
                    if (recordField != null) {
                        columnValue = recordField.toString();
                    }
                    columnGenerated = new StringColumn(columnValue);
                    record.addColumn(columnGenerated);
                }
            } else {
                for (ColumnEntry columnConfig : columnConfigs) {
                    String columnType = columnConfig.getType();
                    Integer columnIndex = columnConfig.getIndex();
                    String columnConst = columnConfig.getValue();

                    String columnValue = null;

                    if (null != columnIndex) {
                        if (null != recordFields.get(columnIndex))
                            columnValue = recordFields.get(columnIndex).toString();
                    } else {
                        columnValue = columnConst;
                    }
                    Type type = Type.valueOf(columnType.toUpperCase());
                    // it's all ok if nullFormat is null
                    if (StringUtils.equals(columnValue, nullFormat)) {
                        columnValue = null;
                    }
                    switch (type) {
                        case STRING:
                            columnGenerated = new StringColumn(columnValue);
                            break;
                        case LONG:
                            try {
                                columnGenerated = new LongColumn(columnValue);
                            } catch (Exception e) {
                                throw new IllegalArgumentException(String.format(
                                        "??????????????????, ?????????[%s] ?????????[%s]", columnValue,
                                        "LONG"));
                            }
                            break;
                        case DOUBLE:
                            try {
                                columnGenerated = new DoubleColumn(columnValue);
                            } catch (Exception e) {
                                throw new IllegalArgumentException(String.format(
                                        "??????????????????, ?????????[%s] ?????????[%s]", columnValue,
                                        "DOUBLE"));
                            }
                            break;
                        case BOOLEAN:
                            try {
                                columnGenerated = new BoolColumn(columnValue);
                            } catch (Exception e) {
                                throw new IllegalArgumentException(String.format(
                                        "??????????????????, ?????????[%s] ?????????[%s]", columnValue,
                                        "BOOLEAN"));
                            }

                            break;
                        case DATE:
                            try {
                                if (columnValue == null) {
                                    columnGenerated = new DateColumn((Date) null);
                                } else {
                                    String formatString = columnConfig.getFormat();
                                    if (StringUtils.isNotBlank(formatString)) {
                                        // ?????????????????????????????????
                                        SimpleDateFormat format = new SimpleDateFormat(
                                                formatString);
                                        columnGenerated = new DateColumn(
                                                format.parse(columnValue));
                                    } else {
                                        // ??????????????????
                                        columnGenerated = new DateColumn(
                                                new StringColumn(columnValue)
                                                        .asDate());
                                    }
                                }
                            } catch (Exception e) {
                                throw new IllegalArgumentException(String.format(
                                        "??????????????????, ?????????[%s] ?????????[%s]", columnValue,
                                        "DATE"));
                            }
                            break;
                        default:
                            String errorMessage = String.format(
                                    "????????????????????????????????? : [%s]", columnType);
                            LOG.error(errorMessage);
                            throw DataXException
                                    .asDataXException(
                                            UnstructuredStorageReaderErrorCode.NOT_SUPPORT_TYPE,
                                            errorMessage);
                    }

                    record.addColumn(columnGenerated);
                }
            }
            recordSender.sendToWriter(record);
        } catch (IllegalArgumentException iae) {
            taskPluginCollector
                    .collectDirtyRecord(record, iae.getMessage());
        } catch (IndexOutOfBoundsException ioe) {
            taskPluginCollector
                    .collectDirtyRecord(record, ioe.getMessage());
        } catch (Exception e) {
            if (e instanceof DataXException) {
                throw (DataXException) e;
            }
            // ??????????????????????????????????????????,?????????????????? & ????????????
            taskPluginCollector.collectDirtyRecord(record, e.getMessage());
        }

        return record;
    }

    private int getAllColumnsCount(String filePath) {
        Path path = new Path(filePath);
        try {
            Reader reader = OrcFile.createReader(path, OrcFile.readerOptions(hadoopConf));
            return reader.getTypes().get(0).getSubtypesCount();
        } catch (IOException e) {
            String message = "??????orcfile column???????????????????????????????????????";
            throw DataXException.asDataXException(HdfsReaderErrorCode.READ_FILE_ERROR, message);
        }
    }
    private int getAllColumnsCount_parquet(String filePath) {
        int columnsCount;
        Path path = new Path(filePath);
        try {
            org.apache.hadoop.conf.Configuration config = new org.apache.hadoop.conf.Configuration();
            ParquetMetadata readerFooter = ParquetFileReader.readFooter(config,path);
            columnsCount = readerFooter.getFileMetaData().getSchema().getColumns().size();
            return columnsCount;
        } catch (IOException e) {
            String message = "??????parquetfile column???????????????????????????????????????";
            throw DataXException.asDataXException(HdfsReaderErrorCode.READ_FILE_ERROR, message);
        }
    }

    private int getMaxIndex(List<ColumnEntry> columnConfigs) {
        int maxIndex = -1;
        for (ColumnEntry columnConfig : columnConfigs) {
            Integer columnIndex = columnConfig.getIndex();
            if (columnIndex != null && columnIndex < 0) {
                String message = String.format("???column????????????index????????????0????????????????????????index,column??????:%s",
                        JSON.toJSONString(columnConfigs));
                LOG.error(message);
                throw DataXException.asDataXException(HdfsReaderErrorCode.CONFIG_INVALID_EXCEPTION, message);
            } else if (columnIndex != null && columnIndex > maxIndex) {
                maxIndex = columnIndex;
            }
        }
        return maxIndex;
    }

    private enum Type {
        STRING, LONG, BOOLEAN, DOUBLE, DATE,
    }

    public boolean checkHdfsFileType(String filepath, String specifiedFileType) {

        Path file = new Path(filepath);

        try {
            FileSystem fs = FileSystem.get(hadoopConf);
            FSDataInputStream in = fs.open(file);

            if (StringUtils.equalsIgnoreCase(specifiedFileType, Constant.CSV)
                    || StringUtils.equalsIgnoreCase(specifiedFileType, Constant.TEXT)) {

                boolean isORC = isORCFile(file, fs, in);// ??????????????? ORC File
                if (isORC) {
                    return false;
                }
                boolean isRC = isRCFile(filepath, in);// ??????????????? RC File
                if (isRC) {
                    return false;
                }
                boolean isSEQ = isSequenceFile(filepath, in);// ??????????????? Sequence File
                if (isSEQ) {
                    return false;
                }
                boolean isPar = isPARFile(file, in);
                if (isPar) {
                    return false;
                }
                // ????????????ORC,RC???SEQ,???????????????TEXT???CSV??????
                return !isORC && !isRC && !isSEQ && !isPar;

            } else if (StringUtils.equalsIgnoreCase(specifiedFileType, Constant.ORC)) {

                return isORCFile(file, fs, in);
            } else if (StringUtils.equalsIgnoreCase(specifiedFileType, Constant.RC)) {

                return isRCFile(filepath, in);
            } else if (StringUtils.equalsIgnoreCase(specifiedFileType, Constant.SEQ)) {

                return isSequenceFile(filepath, in);
            } else if (StringUtils.equalsIgnoreCase(specifiedFileType, Constant.PAR)){
                return isPARFile(file, in);
            }

        } catch (Exception e) {
            String message = String.format("????????????[%s]???????????????????????????ORC,SEQUENCE,RCFile,TEXT,CSV?????????????????????," +
                    "????????????????????????????????????????????????", filepath);
            LOG.error(message);
            throw DataXException.asDataXException(HdfsReaderErrorCode.READ_FILE_ERROR, message, e);
        }
        return false;
    }

    // ??????file?????????ORC File
    private boolean isORCFile(Path file, FileSystem fs, FSDataInputStream in) {
        try {
            // figure out the size of the file using the option or filesystem
            long size = fs.getFileStatus(file).getLen();

            //read last bytes into buffer to get PostScript
            int readSize = (int) Math.min(size, DIRECTORY_SIZE_GUESS);
            in.seek(size - readSize);
            ByteBuffer buffer = ByteBuffer.allocate(readSize);
            in.readFully(buffer.array(), buffer.arrayOffset() + buffer.position(),
                    buffer.remaining());

            //read the PostScript
            //get length of PostScript
            int psLen = buffer.get(readSize - 1) & 0xff;
            int len = OrcFile.MAGIC.length();
            if (psLen < len + 1) {
                return false;
            }
            int offset = buffer.arrayOffset() + buffer.position() + buffer.limit() - 1
                    - len;
            byte[] array = buffer.array();
            // now look for the magic string at the end of the postscript.
            if (Text.decode(array, offset, len).equals(OrcFile.MAGIC)) {
                return true;
            } else {
                // If it isn't there, this may be the 0.11.0 version of ORC.
                // Read the first 3 bytes of the file to check for the header
                in.seek(0);
                byte[] header = new byte[len];
                in.readFully(header, 0, len);
                // if it isn't there, this isn't an ORC file
                if (Text.decode(header, 0, len).equals(OrcFile.MAGIC)) {
                    return true;
                }
            }
        } catch (IOException e) {
            LOG.info(String.format("??????????????????: [%s] ??????ORC File.", file.toString()));
        }
        return false;
    }

    // ??????file?????????RC file
    private boolean isRCFile(String filepath, FSDataInputStream in) {

        // The first version of RCFile used the sequence file header.
        final byte[] ORIGINAL_MAGIC = new byte[]{(byte) 'S', (byte) 'E', (byte) 'Q'};
        // The 'magic' bytes at the beginning of the RCFile
        final byte[] RC_MAGIC = new byte[]{(byte) 'R', (byte) 'C', (byte) 'F'};
        // the version that was included with the original magic, which is mapped
        // into ORIGINAL_VERSION
        final byte ORIGINAL_MAGIC_VERSION_WITH_METADATA = 6;
        // All of the versions should be place in this list.
        final int ORIGINAL_VERSION = 0;  // version with SEQ
        final int NEW_MAGIC_VERSION = 1; // version with RCF
        final int CURRENT_VERSION = NEW_MAGIC_VERSION;
        byte version;

        byte[] magic = new byte[RC_MAGIC.length];
        try {
            in.seek(0);
            in.readFully(magic);

            if (Arrays.equals(magic, ORIGINAL_MAGIC)) {
                byte vers = in.readByte();
                if (vers != ORIGINAL_MAGIC_VERSION_WITH_METADATA) {
                    return false;
                }
                version = ORIGINAL_VERSION;
            } else {
                if (!Arrays.equals(magic, RC_MAGIC)) {
                    return false;
                }

                // Set 'version'
                version = in.readByte();
                if (version > CURRENT_VERSION) {
                    return false;
                }
            }

            if (version == ORIGINAL_VERSION) {
                try {
                    Class<?> keyCls = hadoopConf.getClassByName(Text.readString(in));
                    Class<?> valCls = hadoopConf.getClassByName(Text.readString(in));
                    if (!keyCls.equals(RCFile.KeyBuffer.class)
                            || !valCls.equals(RCFile.ValueBuffer.class)) {
                        return false;
                    }
                } catch (ClassNotFoundException e) {
                    return false;
                }
            }
            boolean decompress = in.readBoolean(); // is compressed?
            if (version == ORIGINAL_VERSION) {
                // is block-compressed? it should be always false.
                boolean blkCompressed = in.readBoolean();
                if (blkCompressed) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            LOG.info(String.format("??????????????????: [%s] ??????RC File.", filepath));
        }
        return false;
    }
    //???????????????parquet
    private boolean isPARFile(Path file, FSDataInputStream in) {
        try {
            GroupReadSupport readSupport = new GroupReadSupport();
            ParquetReader.Builder<Group> reader = ParquetReader.builder(readSupport,file);
            ParquetReader<Group> build = reader.build();
            if(build.read()!=null){
                return true;
            }
        } catch (Exception e) {
            LOG.info(String.format("??????????????????: [%s] ??????PAR File.", file.toString()));
        }
        return false;
    }

    // ??????file?????????Sequence file
    private boolean isSequenceFile(String filepath, FSDataInputStream in) {
        byte[] SEQ_MAGIC = new byte[]{(byte) 'S', (byte) 'E', (byte) 'Q'};
        byte[] magic = new byte[SEQ_MAGIC.length];
        try {
            in.seek(0);
            in.readFully(magic);
            if (Arrays.equals(magic, SEQ_MAGIC)) {
                return true;
            } else {
                return false;
            }
        } catch (IOException e) {
            LOG.info(String.format("??????????????????: [%s] ??????Sequence File.", filepath));
        }
        return false;
    }

}
