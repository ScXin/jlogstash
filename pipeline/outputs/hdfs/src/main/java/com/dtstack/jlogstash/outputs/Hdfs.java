package com.dtstack.jlogstash.outputs;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dtstack.jlogstash.annotation.Required;
import com.dtstack.jlogstash.format.HdfsOutputFormat;
import com.dtstack.jlogstash.format.StoreEnum;
import com.dtstack.jlogstash.format.plugin.HdfsOrcOutputFormat;
import com.dtstack.jlogstash.format.plugin.HdfsTextOutputFormat;
import com.dtstack.jlogstash.render.Formatter;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * 
 * @author sishu.yss
 *
 */
public class Hdfs extends BaseOutput{
	
	private static final long serialVersionUID = -6012196822223887479L;
	
	private static Logger logger = LoggerFactory.getLogger(Hdfs.class);

	private static String hadoopConf = System.getenv("HADOOP_CONF_DIR");
	
	@Required(required = true)
	private static String path ;//模板配置
	
	private static String store = "TEXT";
	
	private static String writeMode = "APPEND";
	
	private static String compression = "NONE";
	
	private static String charsetName = "UTF-8";

	private static String fileName;

	private static Charset charset;
	
	private static String delimiter = "\001";
	
	public static String timezone;

	/**
	 * 间隔 interval 时间对 outputFormat 进行一次 close，触发输出文件的合并
	 */
	public static int interval = 5 * 60 * 1000;
	
	public static int bufferSize = 1024;//bytes
	
    private ExecutorService executor;

	@Required(required = true)
	private static List<String> schema;//["name:varchar"]
	
	private static List<String> columns;
	
	private static List<String> columnTypes;
	
	private static String hadoopUserName = "root";
	
	private static Configuration configuration;

	private static Map<String, Object> hadoopConfigMap;

	private Map<String,HdfsOutputFormat> hdfsOutputFormats = Maps.newConcurrentMap();
	
	private Lock lock = new ReentrantLock();
	
	private AtomicBoolean lockBoolean = new AtomicBoolean(true);

	static{
		Thread.currentThread().setContextClassLoader(null);
	}
	
	public Hdfs(Map config) {
		super(config);
		// TODO Auto-generated constructor stub
	}

	@Override
	public void prepare() {
		// TODO Auto-generated method stub
		try {
			formatSchema();
			setHadoopConfiguration();
			process();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			logger.error("",e);
			System.exit(-1);
		}
	}

	public void process(){
		executor = Executors.newSingleThreadExecutor();
		executor.submit(new Runnable(){

			@Override
			public void run() {
				// TODO Auto-generated method stub
				try {
					while(true){
						Thread.sleep(interval);
						try{
							lock.lockInterruptibly();
							lockBoolean.set(false);
							release();
							logger.warn("hdfs commit again...");
						}finally{
							lockBoolean.set(true);
							lock.unlock();
						}
					}
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					logger.error("",e);
				}
			}
		});
	}
	
	@Override
	protected void emit(Map event) {
		// TODO Auto-generated method stub
		try{
			String realPath = Formatter.format(event, path, timezone);
			getHdfsOutputFormat(realPath).writeRecord(event);
		}catch(Exception e){
			this.addFailedMsg(event);
			logger.error("",e);
		}
	}
	
	public HdfsOutputFormat getHdfsOutputFormat(String realPath) throws IOException{
		HdfsOutputFormat hdfsOutputFormat = null;
		if(!lockBoolean.get()){
			try {
				lock.lockInterruptibly();
				hdfsOutputFormat = hdfsOutputFormats.get(realPath);
			} catch (InterruptedException e) {
				logger.error("",e);
			}finally{
				lock.unlock();
			}
		} else {
			hdfsOutputFormat = hdfsOutputFormats.get(realPath);
		}
		if(hdfsOutputFormat == null){
			if(StoreEnum.TEXT.name().equalsIgnoreCase(store)){
				hdfsOutputFormat = new HdfsTextOutputFormat(configuration,realPath, columns, columnTypes, compression, writeMode, charset, delimiter, fileName);
			}else if(StoreEnum.ORC.name().equalsIgnoreCase(store)){
				hdfsOutputFormat = new HdfsOrcOutputFormat(configuration,realPath, columns, columnTypes, compression, writeMode, charset, fileName);
			} else {
				throw new UnsupportedOperationException("The hdfs store type is unsupported, please use (" + StoreEnum.listStore() + ")");
			}
			hdfsOutputFormat.configure();
			hdfsOutputFormat.open();
			hdfsOutputFormats.put(realPath, hdfsOutputFormat);
		}
		return hdfsOutputFormat;
	}
	
	
	@Override
	public void sendFailedMsg(Object msg){
		emit((Map) msg);
	}
	
	@Override
	public synchronized void release(){
		Set<Map.Entry<String, HdfsOutputFormat>> entrys = hdfsOutputFormats.entrySet();
		for(Map.Entry<String, HdfsOutputFormat> entry:entrys){
			try {
				entry.getValue().close();
				hdfsOutputFormats.remove(entry.getKey());
			} catch (Exception e) {
				// TODO Auto-generated catch block
				logger.error("",e);
			}
		}
	}
	
	private void formatSchema(){
		if(columns == null){
			synchronized(Hdfs.class){
				if(columns == null){
					charset = Charset.forName(charsetName);
					columns = Lists.newArrayList();
					columnTypes = Lists.newArrayList();
			        for(String sche:schema){
			        	String[] sc = sche.split(":");
			        	columns.add(sc[0]);
			        	columnTypes.add(sc[1]);
			        }
				}
			}
		}
	}
	
	private void setHadoopConfiguration() throws Exception{
		if (hadoopConfigMap != null) {
			configuration = new Configuration(false);
			System.setProperty("HADOOP_USER_NAME", hadoopUserName);
			configuration = new Configuration();
			for(Map.Entry<String,Object> entry : hadoopConfigMap.entrySet()) {
				configuration.set(entry.getKey(), entry.getValue().toString());
			}
			configuration.set("fs.hdfs.impl", DistributedFileSystem.class.getName());
		}
		if(configuration == null){
			synchronized(Hdfs.class){
				if(configuration == null){
					System.setProperty("HADOOP_USER_NAME", hadoopUserName);
					configuration = new Configuration();
		    		configuration.set("fs.hdfs.impl", DistributedFileSystem.class.getName());
		            File[] xmlFileList = new File(hadoopConf).listFiles(new FilenameFilter() {
		                @Override
		                public boolean accept(File dir, String name) {
		                    if(name.endsWith(".xml"))
		                        return true;
		                    return false;
		                }
		            });

		            if(xmlFileList != null) {
		                for(File xmlFile : xmlFileList) {
		                	configuration.addResource(xmlFile.toURI().toURL());
		                }
		            }
				}
			}
			
		}
	}
}
