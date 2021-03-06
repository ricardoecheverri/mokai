package org.mokai.connector.camel.jetty;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Properties;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.mokai.Configurable;
import org.mokai.ExposableConfiguration;
import org.mokai.Message;
import org.mokai.MessageProducer;
import org.mokai.Receiver;
import org.mokai.annotation.Resource;

/**
 * 
 * @author German Escobar
 */
public class JettyConnector implements Receiver, Configurable, 
		ExposableConfiguration<JettyConfiguration> {
	
	@Resource
	private MessageProducer messageProducer;
	
	private JettyConfiguration configuration;
	
	private CamelContext camelContext;

	public JettyConnector() {
		this(new JettyConfiguration());
	}
	
	public JettyConnector(JettyConfiguration configuration) {
		this.configuration = configuration;
	}
	
	@Override
	public final void configure() throws Exception {
		camelContext = new DefaultCamelContext();
		
		final String uri = "jetty:http://0.0.0.0:" + getConfiguration().getPort() 
			+ "/" + getConfiguration().getContext();
		

		camelContext.addRoutes(new RouteBuilder(){
	
			@Override
			public void configure() throws Exception {
				from(uri).process(new Processor() {

					@Override
					public void process(Exchange exchange) throws Exception {
						String type = (String) exchange.getIn().getHeader("type");
						if (type == null) {
							type = Message.SMS_TYPE;
						}
						
						Message message = new Message(type);
						
						// retrieve the query part of the request
						String query = (String) exchange.getIn().getHeader("CamelHttpQuery");
						
						// if the query is not null or empty, parse
						if (query != null && !"".equals(query)) {
							
							// load the query parameters in a properties object
							query = query.replaceAll("&", "\n");
							ByteArrayInputStream inputStream = new ByteArrayInputStream(query.getBytes());
							Properties parameters = new Properties();
							parameters.load(inputStream);
							
							// iterate through the parameters and add them to the message properties
							for (Map.Entry<Object,Object> entry : parameters.entrySet()) {
								
								if (!entry.getKey().equals("type")) {
									
									// by default set the key to the header value 
									String key = (String) entry.getKey();
									System.out.println("key: " + key + ", value: " + entry.getValue());
									
									// check if there is a mapping for the key
									if (configuration.getMapper().containsKey(key)) {
										key = configuration.getMapper().get(key);
									}
									
									String value = (String) entry.getValue();
									message.setProperty(key, value);
								}
							}
						}
						
						messageProducer.produce(message);
					}
						
				});
			}
				
		});
			
		camelContext.start();

	}



	@Override
	public final void destroy() throws Exception {
		try {
			camelContext.stop();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public final JettyConfiguration getConfiguration() {
		return configuration;
	}

}
