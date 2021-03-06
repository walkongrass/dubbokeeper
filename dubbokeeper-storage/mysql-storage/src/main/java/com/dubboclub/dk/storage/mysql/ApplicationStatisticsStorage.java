package com.dubboclub.dk.storage.mysql;

import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.dubboclub.dk.storage.model.ApplicationInfo;
import com.dubboclub.dk.storage.model.Statistics;
import com.dubboclub.dk.storage.mysql.mapper.ApplicationMapper;
import com.dubboclub.dk.storage.mysql.mapper.StatisticsMapper;
import org.apache.commons.lang.StringUtils;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * @date: 2015/12/28.
 * @author:bieber.
 * @project:dubbokeeper.
 * @package:com.dubboclub.dk.storage.mysql.
 * @version:1.0.0
 * @fix:
 * @description: 描述功能
 */
public class ApplicationStatisticsStorage  extends Thread{

    private static final String APPLICATION_TEMPLATE="CREATE TABLE `statistics_{}` (\n" +
            "  `id` int(11) NOT NULL AUTO_INCREMENT,\n" +
            "  `timestamp` bigint(1) NOT NULL DEFAULT '0' COMMENT '时间戳',\n" +
            "  `serviceInterface` varchar(255) NOT NULL DEFAULT '' COMMENT '接口名',\n" +
            "  `method` varchar(255) NOT NULL DEFAULT '' COMMENT '方法名',\n" +
            "  `type` varchar(10) DEFAULT NULL COMMENT '当前调用的应用类型',\n" +
            "  `tps` float(11,2) NOT NULL DEFAULT '0.00' COMMENT 'TPS值',\n" +
            "  `kbps` float(11,2) DEFAULT NULL COMMENT '流量',\n" +
            "  `host` varchar(50) DEFAULT NULL COMMENT 'ip地址',\n" +
            "  `elapsed` int(11) DEFAULT NULL COMMENT '耗时',\n" +
            "  `concurrent` int(11) DEFAULT NULL COMMENT '并发数',\n" +
            "  `input` int(11) DEFAULT NULL COMMENT '输入值',\n" +
            "  `output` int(11) DEFAULT NULL COMMENT '输出大小',\n" +
            "  `successCount` int(11) DEFAULT NULL COMMENT '成功次数',\n" +
            "  `failureCount` int(11) DEFAULT NULL COMMENT '失败次数',\n" +
            "  `remoteAddress` varchar(50) DEFAULT NULL COMMENT '远程地址',\n" +
            "  `remoteType` varchar(20) DEFAULT NULL COMMENT '远程应用类型',\n" +
            "  PRIMARY KEY (`id`),\n" +
            "  KEY `time-index` (`timestamp`),\n" +
            "  KEY `method-index` (`method`),\n" +
            "  KEY `service-index` (`serviceInterface`)\n"+
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8;\n";

    private StatisticsMapper statisticsMapper;

    private DataSource dataSource;

    private TransactionTemplate transactionTemplate;

    private ApplicationMapper applicationMapper;

    private ConcurrentLinkedQueue<Statistics> statisticsCollection = new ConcurrentLinkedQueue<Statistics>();

    private String application;

    private volatile long maxElapsed;

    private volatile long maxConcurrent;

    private volatile int maxFault;

    private volatile int maxSuccess;

    private volatile boolean isWriting=false;

    private int type;

    private static final int WRITE_INTERVAL= Integer.parseInt(ConfigUtils.getProperty("mysql.commit.interval", "100"));


    public ApplicationStatisticsStorage(ApplicationMapper applicationMapper,StatisticsMapper statisticsMapper,DataSource dataSource,TransactionTemplate transactionTemplate,String application,int type){
        this(applicationMapper,statisticsMapper,dataSource,transactionTemplate,application,type,false);
    }

    public ApplicationStatisticsStorage(ApplicationMapper applicationMapper,StatisticsMapper statisticsMapper,DataSource dataSource,TransactionTemplate transactionTemplate,String application,int type,boolean needCreateTable){
        this.application = application;
        this.statisticsMapper = statisticsMapper;
        this.dataSource = dataSource;
        this.transactionTemplate = transactionTemplate;
        this.applicationMapper = applicationMapper;
        this.setName(application+"_statisticsWriter");
        this.setDaemon(true);
        if(needCreateTable){
            ApplicationInfo applicationInfo = new ApplicationInfo();
            applicationInfo.setApplicationName(application);
            applicationInfo.setApplicationType(type);
            this.applicationMapper.addApplication(applicationInfo);
            createNewAppTable(application);
        }
        init();
        this.type=type;
    }


    private void init(){
        long end = System.currentTimeMillis();
        long start = System.currentTimeMillis()-24*60*60*1000;
        Long concurrent =statisticsMapper.queryMaxConcurrent(application,null,start,end);
        Long elapsed = statisticsMapper.queryMaxElapsed(application,null,start,end);
        Integer fault = statisticsMapper.queryMaxFault(application,null,start,end);
        Integer success = statisticsMapper.queryMaxSuccess(application,null,start,end);
        maxConcurrent =concurrent==null?0:concurrent;
        maxElapsed = elapsed==null?0:elapsed;
        maxFault=fault==null?0:fault;
        maxSuccess=success==null?0:success;
    }

    public void addStatistics(Statistics statistics){
        while(isWriting){
            //waiting write finished
        }
        if(WRITE_INTERVAL<=0){
            statisticsMapper.addOne(application,statistics);
        }else{
            statisticsCollection.add(statistics);
        }
        if(maxFault<statistics.getFailureCount()){
            maxFault=statistics.getFailureCount();
        }
        if(maxSuccess<statistics.getSuccessCount()){
            maxSuccess=statistics.getSuccessCount();
        }
        if(maxConcurrent<statistics.getConcurrent()){
            maxConcurrent = statistics.getConcurrent();
        }
        if(maxElapsed<statistics.getElapsed()){
            maxElapsed = statistics.getElapsed();
        }
        if((type==0&&statistics.getType()== Statistics.ApplicationType.PROVIDER)||(type==1&&statistics.getType()== Statistics.ApplicationType.CONSUMER)){
            synchronized (this){
                if(type!=2){
                    applicationMapper.updateAppType(application,2);
                    type=2;
                }
            }
        }
    }


    @Override
    public void run() {
        while(true){
            isWriting=true;
            List<Statistics> statisticsList = new ArrayList<Statistics>(statisticsCollection);
            if(statisticsList.size()>0){
                if(batchInsert(statisticsList)){
                    statisticsCollection.clear();
                }
            }
            isWriting=false;
            try {
                Thread.sleep(WRITE_INTERVAL);
            } catch (InterruptedException e) {
                //do nothing
            }
        }
    }

    public boolean batchInsert(final List<Statistics> statisticsList){
        return transactionTemplate.execute(new TransactionCallback<Boolean>() {
            @Override
            public Boolean doInTransaction(TransactionStatus status) {
                int size = statisticsMapper.batchInsert(application,statisticsList);
                if(size!=statisticsList.size()){
                    status.setRollbackOnly();
                    return false;
                }
                return true;
            }
        });
    }

    private boolean  createNewAppTable(final String applicationName){
        return transactionTemplate.execute(new TransactionCallback<Boolean>() {
            @Override
            public Boolean doInTransaction(TransactionStatus status) {
                Connection connection = DataSourceUtils.getConnection(dataSource);
                String tableSql = StringUtils.replace(APPLICATION_TEMPLATE,"{}",applicationName);
                Statement statement=null;
                try {
                    statement = connection.createStatement();
                    statement.execute(tableSql);
                } catch (SQLException e) {
                    e.printStackTrace();
                    status.setRollbackOnly();
                    return false;
                }finally {
                    if(statement!=null){
                        try {
                            statement.close();
                        } catch (SQLException e) {
                            //do nothing
                        }
                    }
                }
                return true;
            }
        });
    }


    public int getMaxSuccess() {
        return maxSuccess;
    }

    public int getMaxFault() {
        return maxFault;
    }

    public long getMaxConcurrent() {
        return maxConcurrent;
    }

    public long getMaxElapsed() {
        return maxElapsed;
    }

    public String getApplication() {
        return application;
    }

    public int getType() {
        return type;
    }
}
