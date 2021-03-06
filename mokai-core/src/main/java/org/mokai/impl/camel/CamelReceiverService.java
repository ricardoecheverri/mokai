package org.mokai.impl.camel;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.camel.CamelContext;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.RouteDefinition;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.Validate;
import org.mokai.Action;
import org.mokai.Configurable;
import org.mokai.ExecutionException;
import org.mokai.Message;
import org.mokai.MessageProducer;
import org.mokai.ObjectAlreadyExistsException;
import org.mokai.ObjectNotFoundException;
import org.mokai.Receiver;
import org.mokai.ReceiverService;
import org.mokai.Serviceable;
import org.mokai.Message.Flow;
import org.mokai.Message.SourceType;
import org.mokai.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ReceiverService} implementation based on Apache Camel.
 * 
 * @author German Escobar
 */
public class CamelReceiverService implements ReceiverService {
	
	private Logger log = LoggerFactory.getLogger(CamelReceiverService.class);
	
	private String id;
	
	private Receiver receiver;
	
	private List<Action> postReceivingActions;
	
	private State state;
	
	private CamelContext camelContext;
	
	private List<RouteDefinition> routes;
	
	public CamelReceiverService(String id, Receiver receiver, CamelContext camelContext) 
			throws IllegalArgumentException, ExecutionException {
		
		Validate.notEmpty(id, "An Id must be provided");
		Validate.notNull(receiver, "A connector must be provided");
		Validate.notNull(camelContext, "A CamelContext must be provided");
		
		id = StringUtils.lowerCase(id);
		this.id = StringUtils.deleteWhitespace(id);
		this.receiver = receiver;
	
		this.state = State.STOPPED;
		this.postReceivingActions = new ArrayList<Action>();
		
		this.camelContext = camelContext;

		init();
	}
	
	private void init() {
		// add the message producer to connector
		addMessageProducerToConnector(receiver);		
		
		try {
			
			// configure connector
			if (Configurable.class.isInstance(receiver)) {
				Configurable configurableConnector = (Configurable) receiver;
				configurableConnector.configure();
			}
			
			RouteBuilder routeBuilder = new RouteBuilder() {
		
				@Override
				public void configure() throws Exception {
					// from the component that receives the messages from the MessageProducer	
					RouteDefinition route = from(getEndpointUri());
					
					// add the source and set type to Message.Type.OUTBOUND
					route.process(new CompleteMessageProcessor());
					
					// execute all the post receiving actions
					route.process(new ActionsProcessor(postReceivingActions));
					
					// route to the outbound router
					route.to("activemq:outboundRouter");
				}
					
			};
				
			camelContext.addRoutes(routeBuilder);
			routes = routeBuilder.getRouteCollection().getRoutes();
				
		} catch (Exception e) {
			throw new ExecutionException(e);
		}
	}
	
	private void addMessageProducerToConnector(Object connector) {
		MessageProducer messageProducer = new MessageProducer() {
			
			ProducerTemplate producer = camelContext.createProducerTemplate();

			@Override
			public void produce(Message message) {
				Validate.notNull(message);
				
				try {
					producer.sendBody(getEndpointUri(), message);
				} catch (CamelExecutionException e) {
					Throwable ex = e;
					if (e.getCause() != null) {
						ex = e.getCause();
					}
					throw new ExecutionException(ex);
				}
			}
			
		};
		
		Field[] fields = connector.getClass().getDeclaredFields();
		for (Field field : fields) {
			
			if (field.isAnnotationPresent(Resource.class) 
					&& field.getType().isInstance(messageProducer)) {
				field.setAccessible(true);
				try {
					field.set(connector, messageProducer);
				} catch (Exception e) {
					throw new ExecutionException(e);
				} 
			}
		}
	}
	
	@Override
	public final String getId() {
		return this.id;
	}
	
	@Override
	public final Receiver getReceiver() {
		return this.receiver;
	}

	private boolean isServiceable() {
		if (Serviceable.class.isInstance(receiver)) {
			return true;
		}
		
		return false;
	}

	@Override
	public final ReceiverService addPostReceivingAction(Action action) throws IllegalArgumentException, 
			ObjectAlreadyExistsException {
		
		Validate.notNull(action);
		
		if (postReceivingActions.contains(action)) {
			throw new ObjectAlreadyExistsException("Action " + action + " already exists");
		}
		
		postReceivingActions.add(action);
		
		return this;
	}
	
	@Override
	public final ReceiverService removePostReceivingAction(Action action) throws IllegalArgumentException, 
			ObjectNotFoundException {
		
		Validate.notNull(action);
		
		boolean removed = postReceivingActions.remove(action);
		if (!removed) {
			throw new ObjectNotFoundException("Action " + action + " not found");
		}
		
		return this;
	}

	@Override
	public final List<Action> getPostReceivingActions() {
		return Collections.unmodifiableList(postReceivingActions);
	}


	@Override
	public final State getState() {
		return this.state;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final void start() throws ExecutionException {
		
		try {
			
			if (!state.isStartable()) {
				log.warn("Receiver " + id + " is already started, ignoring call");
				return;
			}
			
			// start the connector if is Serviceable
			if (isServiceable()) {
				Serviceable connectorService = (Serviceable) receiver;
				connectorService.doStart();
			} else {
				log.warn("Receiver " + id + " is not Serviceable, ignoring call");
			}
			
			state = State.STARTED;
			
		} catch (Exception e) {
			throw new ExecutionException(e);
		}
		
	}

	@Override
	public final void stop() throws ExecutionException {
		try {
			
			if (!state.isStoppable()) {
				log.warn("Receiver is already stopped, ignoring call");
				return;
			}
			
			if (isServiceable()) {
				Serviceable connectorService = (Serviceable) receiver;
				connectorService.doStop();
			} else {
				log.warn("Receiver " + id + " is not Serviceable, ignoring call");
			}
			
			state = State.STOPPED;
			
		} catch (Exception e) {
			throw new ExecutionException(e);
		}
	}
	
	@Override
	public final void destroy() {
		try {
			
			stop();
			
			// invoke the remove method on the connector
			if (Configurable.class.isInstance(receiver)) {
				Configurable configurableConnector = (Configurable) receiver;
				configurableConnector.destroy();
			}
			
			// stop the routes
			for (RouteDefinition route : routes) {
				camelContext.stopRoute(route);
			}
		} catch (Exception e) {
			log.warn("Exception destroying receiver " + id + ": " + e.getMessage(), e);
		}
	}

	private String getEndpointUri() {
		return "direct:receiver-" + id;
	}
	
	private class CompleteMessageProcessor implements Processor {

		@Override
		public void process(Exchange exchange) throws Exception {
			Message message = exchange.getIn().getBody(Message.class);
			
			message.setSourceType(SourceType.RECEIVER);
			message.setSource(id);
			message.setFlow(Flow.OUTBOUND);
		}
		
	}

}
