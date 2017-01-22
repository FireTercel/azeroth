package cn.com.warlock.kafka.consumer;

import java.io.Closeable;
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.common.serialization.Deserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.com.warlock.kafka.handler.MessageHandler;
import cn.com.warlock.kafka.message.DefaultMessage;
import cn.com.warlock.kafka.serializer.MessageDecoder;
import cn.com.warlock.kafka.thread.StandardThreadExecutor;
import cn.com.warlock.kafka.thread.StandardThreadExecutor.StandardThreadFactory;
import kafka.consumer.ConsumerConfig;
import kafka.consumer.ConsumerIterator;
import kafka.consumer.KafkaStream;
import kafka.javaapi.consumer.ConsumerConnector;
import kafka.serializer.StringDecoder;
import kafka.utils.VerifiableProperties;

public class OldApiTopicConsumer implements TopicConsumer,Closeable {


	private final static Logger logger = LoggerFactory.getLogger(OldApiTopicConsumer.class);
	
	private ConsumerConnector connector;
	
	private Deserializer<Object> deserializer;
	//
	private Map<String, MessageHandler> topics;
	//接收线程
	private StandardThreadExecutor fetchExecutor;
	//默认处理线程
	private StandardThreadExecutor defaultProcessExecutor;
	
	//执行线程池满了被拒绝任务处理线程池
	private ExecutorService poolRejectedExecutor = Executors.newSingleThreadExecutor();
	
	private AtomicBoolean runing = new AtomicBoolean(false);
	
	private ErrorMessageDefaultProcessor errorMessageProcessor = new ErrorMessageDefaultProcessor(1);

	/**
	 * 
	 * @param connector
	 * @param topics
	 * @param processThreads 
	 */
	@SuppressWarnings("unchecked")
	public OldApiTopicConsumer(Properties configs, Map<String, MessageHandler> topics,int maxProcessThreads) {
		
		try {
			Class<?> deserializerClass = Class.forName(configs.getProperty("value.deserializer"));
			deserializer = (Deserializer<Object>) deserializerClass.newInstance();
		} catch (Exception e) {}
		this.connector = kafka.consumer.Consumer.createJavaConsumerConnector(new ConsumerConfig(configs));
		this.topics = topics;
		
		int poolSize = topics.size();
		this.fetchExecutor = new StandardThreadExecutor(poolSize, poolSize,0, TimeUnit.SECONDS, poolSize,new StandardThreadFactory("KafkaFetcher"));
		
		this.defaultProcessExecutor = new StandardThreadExecutor(1, maxProcessThreads,30, TimeUnit.SECONDS, maxProcessThreads,new StandardThreadFactory("KafkaProcessor"),new PoolFullRunsPolicy());
		
		logger.info("Kafka Conumer ThreadPool initialized,fetchPool Size:{},defalutProcessPool Size:{} ",poolSize,maxProcessThreads);
	}


	@Override
	public void start() {
		Map<String, Integer> topicCountMap = new HashMap<String, Integer>();
		for (String topicName : topics.keySet()) {
			int nThreads = 1;
			topicCountMap.put(topicName, nThreads);
			logger.info("topic[{}] assign fetch Threads {}",topicName,nThreads);
		}
		
		StringDecoder keyDecoder = new StringDecoder(new VerifiableProperties());
		MessageDecoder valueDecoder = new MessageDecoder(deserializer);

		Map<String, List<KafkaStream<String, Object>>> consumerMap = this.connector.createMessageStreams(topicCountMap,
				keyDecoder, valueDecoder);

		for (String topicName : topics.keySet()) {
			final List<KafkaStream<String, Object>> streams = consumerMap.get(topicName);

			for (final KafkaStream<String, Object> stream : streams) {
				MessageProcessor processer = new MessageProcessor(topicName, stream);
				this.fetchExecutor.execute(processer);
			}
		}
		//
		runing.set(true);
	}

	/**
	 * 消息处理器
	 */
	class MessageProcessor implements Runnable {

		KafkaStream<String, Object> stream;

		private String topicName;
		
		private MessageHandler messageHandler;
		
		private String processorName;
		public MessageProcessor(String topicName, KafkaStream<String, Object> stream) {
			this.stream = stream;
			this.topicName = topicName;
			this.messageHandler = topics.get(topicName);
			this.processorName = this.messageHandler.getClass().getName();
		}

		@Override
		public void run() {
 
			if (logger.isInfoEnabled()) {
				logger.info("MessageProcessor [{}] start, topic:{}",Thread.currentThread().getName(),topicName);
			}

			ConsumerIterator<String, Object> it = stream.iterator();
			// 没有消息的话，这里会阻塞
			while (it.hasNext()) {
				
				//当处理线程满后，阻塞处理线程
				while(true){
					if(defaultProcessExecutor.getMaximumPoolSize() > defaultProcessExecutor.getSubmittedTasksCount()){
						break;
					}
					try {Thread.sleep(200);} catch (Exception e) {}
				}
				try {					
					Object _message = it.next().message();
					DefaultMessage message = null;
					try {
						message = (DefaultMessage) _message;
					} catch (ClassCastException e) {
						message = new DefaultMessage((Serializable) _message);
					}
					//第一阶段处理
					messageHandler.p1Process(message);
					//第二阶段处理
					submitMessageToProcess(topicName,message);
				} catch (Exception e) {
					logger.error("received_topic_error,topic:"+topicName,e);
				}
				
			}
		
		}
		
		/**
		 * 提交消息到处理线程队列
		 * @param message
		 */
		private void submitMessageToProcess(final String topicName,final DefaultMessage message) {
			defaultProcessExecutor.submit(new Runnable() {
				@Override
				public void run() {
					try {	
						long start = logger.isDebugEnabled() ? System.currentTimeMillis() : 0;
						messageHandler.p2Process(message);
						if(logger.isDebugEnabled()){
							long useTime = System.currentTimeMillis() - start;
							if(useTime > 1000)logger.debug("received_topic_useTime [{}]process topic:{} use time {} ms",processorName,topicName,useTime);
						}
					} catch (Exception e) {
						boolean processed = messageHandler.onProcessError(message);
						if(processed == false){
							errorMessageProcessor.submit(message, messageHandler);
						}
						logger.error("received_topic_process_error ["+processorName+"]processMessage error,topic:"+topicName,e);
					}
				}
			});
		}
		
	}

	@Override
	public void close() {
		if(!runing.get())return;
		this.fetchExecutor.shutdown();
		this.defaultProcessExecutor.shutdown();
		this.poolRejectedExecutor.shutdown();
		this.connector.commitOffsets();
		this.connector.shutdown();
		runing.set(false);
		this.errorMessageProcessor.close();
		logger.info("KafkaTopicSubscriber shutdown ok...");
	}
	
	/**
	 * 处理线程满后策略
	 */
	private class PoolFullRunsPolicy implements RejectedExecutionHandler {
		
        public PoolFullRunsPolicy() {}
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
        	poolRejectedExecutor.execute(r);
        }
    }
		
}
