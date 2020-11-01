package producer;

import com.google.common.collect.Lists;
import com.twitter.hbc.ClientBuilder;
import com.twitter.hbc.core.Client;
import com.twitter.hbc.core.Constants;
import com.twitter.hbc.core.Hosts;
import com.twitter.hbc.core.HttpHosts;
import com.twitter.hbc.core.endpoint.StatusesFilterEndpoint;
import com.twitter.hbc.core.processor.StringDelimitedProcessor;
import com.twitter.hbc.httpclient.auth.Authentication;
import com.twitter.hbc.httpclient.auth.OAuth1;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class TwitterProducer {

    private final Logger logger = LoggerFactory.getLogger(TwitterProducer.class);

    private String consumerKey = "WsAeTgDSBnlwBZWw6KaijPN9b"; //API Key
    private String consumerSecret = "BfCqpAJiAQRGRI8PAo964CNEG6eck6IV6kGndZypeI4eDLF3B2"; //API Secret Key
    private String token = "2932095118-XtnODAvArwq61KABZnirEgBBchd66mWwQIwpbIO"; //Access Token
    private String secret = "54AM3hVYQH9wr17pGPJjavQS2CFJribm0I2YGWO3zJ2uDS"; //Access Token Secret

    public TwitterProducer() {}

    public static void main(String[] args) {
        new TwitterProducer().run();
    }

    public void run() {
        logger.info("Setup");

        // Step1 : create a twitter client
        /** Set up your blocking queues: Be sure to size these properly based on expected TPS of your stream */
        BlockingQueue<String> msgQueue = new LinkedBlockingQueue<String>(1000);
        Client twitterClient = createTwitterClient(msgQueue);
        twitterClient.connect();// Attempts to establish a connection.

        // Step2 : create a kafka producer
        KafkaProducer<String, String> producer = createKafkaProducer();

        // add a shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Stopping application...");
            logger.info("Shutting down client from twitter...");
            twitterClient.stop();
            logger.info("Closing producer...");
            producer.close();//it send all the messages that kept in memory to kafka before gets shutdown
            logger.info("done!");
        }));

        // Step3 : loop to sends tweets to kafka
        // on a different thread, or multiple different threads....
        while (!twitterClient.isDone()) {
            String msg = null;
            try {
                msg = msgQueue.poll(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
                twitterClient.stop();
            }

            if(msg != null){
                logger.info(msg);
                producer.send(new ProducerRecord<>("twitter_tweets", null, msg), new Callback() {
                    @Override
                    public void onCompletion(RecordMetadata recordMetadata, Exception e) {
                        if(e != null){
                            logger.error("Something bad happened", e);
                        }
                    }
                });
            }
        }
        logger.info("End of application!");
    }

    private KafkaProducer<String, String> createKafkaProducer() {
        // Step1 : Create Producer Properties
        // --> you can get a list of config options in :
        // https://kafka.apache.org/documentation/#producerconfigs
        Properties properties = new Properties();
        properties.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:9092");
        //because kafka client converts the messages to bytes and send to kafka we have to set these two props to
        // know how to serialize(convert to byte) them and in this case both are String so we use StringSerializer
        properties.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        // create Safe Producer
        properties.setProperty(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        //below properties are not required just to make sure:
        properties.setProperty(ProducerConfig.ACKS_CONFIG, "all");
        properties.setProperty(ProducerConfig.RETRIES_CONFIG, String.valueOf(Integer.MAX_VALUE));
        properties.setProperty(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5"); // kafka 2.0 >= 1.1

        // create high throughput producer
        properties.setProperty(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        properties.setProperty(ProducerConfig.LINGER_MS_CONFIG, "20");
        properties.setProperty(ProducerConfig.BATCH_SIZE_CONFIG, Integer.toString(32*1024)); // 32 KB

        // Step2 : Create the Producer
        KafkaProducer<String, String> kafkaProducer = new KafkaProducer<String, String>(properties);

        return kafkaProducer;
    }

    public Client createTwitterClient(BlockingQueue<String> msgQueue){


        /** Declare the host you want to connect to, the endpoint, and authentication (basic auth or oauth) */
        Hosts hosebirdHosts = new HttpHosts(Constants.STREAM_HOST);
        StatusesFilterEndpoint hosebirdEndpoint = new StatusesFilterEndpoint();

        List<String> terms = Lists.newArrayList("kafka", "bitcoin", "usa", "soccer");
        hosebirdEndpoint.trackTerms(terms);

        // These secrets should be read from a config file
        Authentication hosebirdAuth = new OAuth1(consumerKey, consumerSecret, token, secret);

        ClientBuilder builder = new ClientBuilder()
                .name("Hosebird-Client-01")                              // optional: mainly for the logs
                .hosts(hosebirdHosts)
                .authentication(hosebirdAuth)
                .endpoint(hosebirdEndpoint)
                .processor(new StringDelimitedProcessor(msgQueue));

        Client hosebirdClient = builder.build();

        return hosebirdClient;
    }
}
