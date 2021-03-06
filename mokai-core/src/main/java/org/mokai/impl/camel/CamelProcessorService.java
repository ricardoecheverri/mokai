package org.mokai.impl.camel;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.camel.CamelContext;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.spi.BrowsableEndpoint;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.Validate;
import org.mokai.Acceptor;
import org.mokai.Action;
import org.mokai.Configurable;
import org.mokai.ExecutionException;
import org.mokai.Message;
import org.mokai.MessageProducer;
import org.mokai.MonitorStatusBuilder;
import org.mokai.Monitorable;
import org.mokai.ObjectAlreadyExistsException;
import org.mokai.ObjectNotFoundException;
import org.mokai.Processor;
import org.mokai.ProcessorService;
import org.mokai.Serviceable;
import org.mokai.Message.DestinationType;
import org.mokai.Monitorable.Status;
import org.mokai.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ProcessorService} implementation based on Apache Camel.
 * 
 * @author German Escobar
 */
public class CamelProcessorService implements ProcessorService {
	
	private Logger log = LoggerFactory.getLogger(CamelProcessorService.class);
	
	private String id;
	
	private int priority;
	
	private List<Acceptor> acceptors;
	
	private Processor processor;
	
	private List<Action> preProcessingActions;
	
	private List<Action> postProcessingActions;
	
	private List<Action> postReceivingActions;
	
	private RedeliveryPolicy redeliveryPolicy;
	
	private State state;
	
	private CamelContext camelContext;
	
	private ProducerTemplate camelProducer;
	
	private List<RouteDefinition> routes;
	
	private Status status = Status.UNKNOWN;
	
	private int failedMessages;
	
	/**
	 * Constructor. Removes spaces from id argument and lower case it.
	 * @param id the id of the processor service. Shouldn't be null or empty.
	 * @param priority the priority of the processor service (can be positive
	 * or negative)
	 * @param processor the {@link Processor} implementation
	 * @param camelContext a started {@link CamelContext} implementation.
	 * @throws IllegalArgumentException if the id arg is null or empty, or if the 
	 * processor arg is null.
	 * @throws ExecutionException if an exception is thrown configuring the processor
	 */
	public CamelProcessorService(String id, int priority, Processor processor, CamelContext camelContext) 
			throws IllegalArgumentException, ExecutionException {
		
		Validate.notEmpty(id);
		Validate.notNull(processor);
		Validate.notNull(camelContext);
		
		id = StringUtils.lowerCase(id);
		this.id = StringUtils.deleteWhitespace(id);
		this.priority = priority;
		this.processor = processor;
		
		this.state = State.STOPPED;
		
		this.acceptors = new ArrayList<Acceptor>();
	
		this.preProcessingActions = new ArrayList<Action>();
		this.postProcessingActions = new ArrayList<Action>();
		this.postReceivingActions = new ArrayList<Action>();
		
		this.redeliveryPolicy = new RedeliveryPolicy();
		
		this.camelContext = camelContext;
		this.camelProducer = camelContext.createProducerTemplate();
		
		try {
			configureProcessor(processor);
		} catch (Exception e) {
			throw new ExecutionException(e);
		}
	}
	
	private void configureProcessor(Processor processor) throws Exception {
		addMessageProducerToConnector(processor);
		
		if (Configurable.class.isInstance(processor)) {
			Configurable configurableProcessor = (Configurable) processor;
			configurableProcessor.configure();
		}
	}
	
	private void addMessageProducerToConnector(Object connector) {
		MessageProducer messageProducer = new MessageProducer() {
			
			ProducerTemplate producer = camelContext.createProducerTemplate();

			@Override
			public void produce(Message message) {
				Validate.notNull(message);
				
				try {
					producer.sendBody(getInternalUri(), message);
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
	public final int getPriority() {
		return this.priority;
	}

	@Override
	public void setPriority(int priority) {
		this.priority = priority;
	}

	@Override
	public final Processor getProcessor() {
		return this.processor;
	}
	
	@Override
	public final int getNumQueuedMessages() {
		BrowsableEndpoint queueEndpoint = camelContext.getEndpoint("activemq:processor-" + id, BrowsableEndpoint.class);
		return queueEndpoint.getExchanges().size();
	}

	@Override
	public final ProcessorService addAcceptor(Acceptor acceptor) throws IllegalArgumentException, 
			ObjectAlreadyExistsException {
		
		Validate.notNull(acceptor);
		
		if (acceptors.contains(acceptor)) {
			throw new ObjectAlreadyExistsException("Acceptor " + acceptor + " already exists");
		}
		
		this.acceptors.add(acceptor);
		
		return this;
	}
	
	@Override
	public final ProcessorService removeAcceptor(Acceptor acceptor) throws IllegalArgumentException, 
			ObjectNotFoundException {
		
		Validate.notNull(acceptor);
		
		boolean removed = acceptors.remove(acceptor);
		if (!removed) {
			throw new ObjectNotFoundException("Acceptor " + acceptor + " not found");
		}
		
		return this;
	}
	
	@Override
	public final List<Acceptor> getAcceptors() {
		return Collections.unmodifiableList(acceptors);
	}
	
	@Override
	public final ProcessorService addPreProcessingAction(Action action) throws IllegalArgumentException, 
			ObjectAlreadyExistsException {
		
		Validate.notNull(action);
		
		if (preProcessingActions.contains(action)) {
			throw new ObjectAlreadyExistsException("Action " + action + " already exists");
		}
		
		this.preProcessingActions.add(action);
		
		return this;
	}
	
	@Override
	public final ProcessorService removePreProcessingAction(Action action) throws IllegalArgumentException, 
			ObjectNotFoundException {
		
		Validate.notNull(action);
		
		boolean removed = preProcessingActions.remove(action);
		if (!removed) {
			throw new ObjectNotFoundException("Action " + action + " not found");
		}
		
		return this;
	}
	
	@Override
	public final List<Action> getPreProcessingActions() {
		return Collections.unmodifiableList(preProcessingActions);
	}

	@Override
	public final ProcessorService addPostProcessingAction(Action action) throws IllegalArgumentException, 
			ObjectAlreadyExistsException {
		
		Validate.notNull(action);
		
		if (postProcessingActions.contains(action)) {
			throw new ObjectAlreadyExistsException("Action " + action + " already exists");
		}
		
		this.postProcessingActions.add(action);
		
		return this;
	}
	
	@Override
	public final ProcessorService removePostProcessingAction(Action action) throws IllegalArgumentException,
			ObjectNotFoundException {
		
		Validate.notNull(action);
		
		boolean removed = postProcessingActions.remove(action);
		if (!removed) {
			throw new ObjectNotFoundException("Action " + action + " not found");
		}
		
		return this;
	}
	
	@Override
	public final List<Action> getPostProcessingActions() {
		return Collections.unmodifiableList(postProcessingActions);
	}

	@Override
	public final ProcessorService addPostReceivingAction(Action action) throws IllegalArgumentException, 
			ObjectAlreadyExistsException {
		
		Validate.notNull(action);
		
		if (postReceivingActions.contains(action)) {
			throw new ObjectAlreadyExistsException("Action " + action + " already exists");
		}
		
		this.postReceivingActions.add(action);
		
		return this;
	}
	
	@Override
	public final ProcessorService removePostReceivingAction(Action action) throws IllegalArgumentException, 
			ObjectNotFoundException {
		
		Validate.notNull(action);
		
		boolean remove = postReceivingActions.remove(action);
		if (!remove) {
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
		return state;
	}

	@Override
	public final Status getStatus() {
		
		Status retStatus = status; // the status we are returning
		
		// check if the processor is monitorable
		if (Monitorable.class.isInstance(processor)) {
			Monitorable monitorable = (Monitorable) processor;
			retStatus = monitorable.getStatus();
		}
		
		// resolve conflicts
		if (retStatus.equals(Status.OK) && status.equals(Status.FAILED)) {
			String message = status.getMessage();
			message = "Processor is OK but " + message;
			
			status.setMessage(message);
			
			retStatus = status;
		} 
		
		return retStatus;
	}

	@Override
	public final void start() throws ExecutionException {
		
		if (!state.isStartable()) {
			log.warn("Processor " + id + " is already started, ignoring call");
			return;
		}
		
		try {
			
			// start the connector if is Serviceable
			if (Serviceable.class.isInstance(processor)) {
				Serviceable connectorService = (Serviceable) processor;
				connectorService.doStart();
			}
			
			// check if the routes already exists and start them
			if (routes != null) {
				
				for (RouteDefinition route : routes) {
					camelContext.startRoute(route);
				}
				
			} else { // if no routes yet then create them!
				
				RouteBuilder outboundRouteBuilder = new RouteBuilder() {

					@Override
					public void configure() throws Exception {
						onException(Exception.class).process(new org.apache.camel.Processor() {

							@Override
							public void process(Exchange exchange) throws Exception {
								Message message = exchange.getIn().getBody(Message.class);
								message.setStatus(Message.Status.FAILED);
							}
							
						}).to("activemq:failedmessages");
						
						//errorHandler(deadLetterChannel("activemq:failedmessages").maximumRedeliveries(1).maximumRedeliveryDelay(1000));
						
						ActionsProcessor preProcessingActionsProcessor = new ActionsProcessor(preProcessingActions);
						ActionsProcessor postProcessingActionsProcessor = new ActionsProcessor(postProcessingActions);
						
						// from the queue
						RouteDefinition route = from(getQueueUri());
						
						// set the source and type of the Message
						route.process(new OutboundMessageProcessor());
						
						// execute the pre-processing actions
						route.process(preProcessingActionsProcessor);
						
						// call the processor (this actually process the message)
						route.process(new ConnectorProcessor());
						
						// execute the post-processing actions
						route.process(postProcessingActionsProcessor);
						
						// route to the processed messages
						route.to("direct:processedmessages");
							
					}
					
				};
			
				RouteBuilder inboundRouteBuilder = new RouteBuilder() {
		
					@Override
					public void configure() throws Exception {
						ActionsProcessor postReceivingActionsProcessor = new ActionsProcessor(postReceivingActions);
						
						// from the component that receives the messages from the MessageProducer	
						RouteDefinition route = from(getInternalUri());
						
						// add the source and type of the Message
						route.process(new InboundMessageProcessor());
						
						// execute the post-receiving actions
						route.process(postReceivingActionsProcessor);
						
						// route to the inbound router
						route.to("activemq:inboundRouter");
					}
					
				};
				
				camelContext.addRoutes(outboundRouteBuilder);
				camelContext.addRoutes(inboundRouteBuilder);
				
				routes = outboundRouteBuilder.getRouteCollection().getRoutes();
				routes.addAll(inboundRouteBuilder.getRouteCollection().getRoutes());
				
			}
			
			state = State.STARTED;
		} catch (Exception e) {
			throw new ExecutionException(e);
		}
	}

	@Override
	public final void stop() {
		try {
			if (!state.isStoppable()) {
				log.warn("Processor " + id + " is already stopped, ignoring call");
				return;
			}
			
			// stop the connector if is Serviceable
			if (Serviceable.class.isInstance(processor)) {
				Serviceable connectorService = (Serviceable) processor;
				connectorService.doStop();
			}
			
			// stop the routes
			for (RouteDefinition route : routes) {
				camelContext.stopRoute(route);
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
			
			if (Configurable.class.isInstance(processor)) {
				Configurable configurableProcessor = (Configurable) processor;
				configurableProcessor.destroy();
			}
			
		} catch (Exception e) {
			log.warn("Exception destroying processor " + id + ": " + e.getMessage(), e);
		}
	}

	private String getQueueUri() {
		return "activemq:processor-" + id;
	}
	
	private String getInternalUri() {
		return "direct:processor-" + id;
	}
	
	public final void setRedeliveryPolicy(RedeliveryPolicy redeliveryPolicy) {
		this.redeliveryPolicy = redeliveryPolicy;
	}
	
	@Override
	public String toString() {
		return id;
	}

	private class ConnectorProcessor implements org.apache.camel.Processor {
		
		@Override
		public void process(Exchange exchange) throws Exception {
			Message message = exchange.getIn().getBody(Message.class);

			// we know we support the message
			boolean success = process(message, 1);
			if (!success) {
				exchange.setProperty(Exchange.ROUTE_STOP, true);
			}
		}
		
		private boolean process(Message message, int attempt) {
			
			try {
				// try to process the message
				processor.process(message);
				message.setStatus(Message.Status.PROCESSED);
				
				status = MonitorStatusBuilder.ok();
				failedMessages = 0;
				
				return true;
			} catch (Exception e) {
				
				// only retry if we haven't exceeded the max redeliveries
				int maxRetries = redeliveryPolicy.getMaxRedeliveries();
				if (attempt < maxRetries) {
					log.warn("message failed, retrying " + attempt + " of " + maxRetries);
					
					// wait redelivery delay
					long delay = redeliveryPolicy.getMaxRedeliveryDelay();
					try { this.wait(delay); } catch (Exception f) { }
					
					// retry
					attempt++;
					process(message, attempt);
				} else {
					log.error("message failed after " + maxRetries + " retries: " + e.getMessage(), e);
					
					// set the new status
					failedMessages++;
					String failMessage = failedMessages +
						(failedMessages == 1 ? " message has " : " messages have") + "failed.";
					status = MonitorStatusBuilder.failed(failMessage, e);
					
					// send to failed messages
					message.setStatus(Message.Status.FAILED);
					camelProducer.sendBody("activemq:failedmessages", message);
					
				}
				
				return false;
				
			}
		}
		
	}
	
	/**
	 * 
	 * 
	 * @author German Escobar
	 */
	private class OutboundMessageProcessor implements org.apache.camel.Processor {
		
		@Override
		public void process(Exchange exchange) throws Exception {
			Message message = exchange.getIn().getBody(Message.class);
			
			message.setDestination(id);
			message.setDestinationType(DestinationType.PROCESSOR);
		}
	}
	
	/**
	 * 
	 * 
	 * @author German Escobar
	 */
	private class InboundMessageProcessor implements org.apache.camel.Processor {
		
		@Override
		public void process(Exchange exchange) throws Exception {
			Message message = exchange.getIn().getBody(Message.class);
			
			message.setSource(id);
			message.setSourceType(Message.SourceType.PROCESSOR);
			message.setFlow(Message.Flow.INBOUND);
		}
		
	}

}
