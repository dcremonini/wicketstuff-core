package org.wicketstuff.push.cometd;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import org.apache.wicket.Application;
import org.apache.wicket.Component;
import org.apache.wicket.Session;
import org.apache.wicket.protocol.http.WebApplication;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.bayeux.server.ServerSession.MessageListener;
import org.cometd.bayeux.server.ServerSession.RemoveListener;
import org.wicketstuff.push.ChannelEvent;
import org.wicketstuff.push.IChannelListener;
import org.wicketstuff.push.IChannelService;

/**
 * Cometd based implementation of {@link IChannelService}.
 * <p>
 * This service is based on cometd client implemented by the dojo toolkit, which
 * must be embedded in your application.
 * <p>
 * This implementation relies on cometd for updating the page, but actually uses
 * regular cometd events, which, if a channel listener is properly installed on
 * a component of the page using
 * {@link #addChannelListener(Component, String, IChannelListener)}, will
 * trigger a wicket ajax call to get the page actually refreshed using regular
 * wicket ajax mechanisms.
 * <p>
 * This mean that each time an event is published, a new connection is made to
 * the server to get the actual page update required by the
 * {@link IChannelListener}.
 * 
 * @author Xavier Hanin
 * @author Rodolfo Hansen
 * @see IChannelService
 */
public class CometdService implements IChannelService {

	private final class RemovalForwardingListener implements MessageListener {

		public boolean onMessage(final ServerSession fromClient,
				final ServerSession toClient, final ServerMessage msg) {
			final String channel = (String) msg.get("subscription");
			if (removalListeners.containsKey(channel)) {
				fromClient.addListener(removalListeners.get(channel));
			}
			return true;
		}
	}

	private final class RemovalListener implements RemoveListener {

		private final RemoveListener removeListener;
		private final Session sess;

		public RemovalListener(final RemoveListener listener, final Session sess) {
			removeListener = listener;
			this.sess = sess;
		}

		public void removed(final ServerSession session, final boolean timeout) {
			final boolean hasNoApp = !Application.exists();
			final boolean hasNoSession = !Session.exists();
			if (hasNoApp) {
				Application.set(getApplication());
			}
			if (hasNoSession && sess != null) {
				Session.set(sess);
			}

			removeListener.removed(session, timeout);

			if (hasNoApp) {
				Application.unset();
			}
			if (hasNoSession) {
				Session.unset();
			}
		}
	}

	public static final String BAYEUX_CLIENT_PREFIX = "wicket-push";

	private final Map<String, RemoveListener> removalListeners;

	final WebApplication _application;
	private BayeuxServer _bayeux;
	private ServerSession serviceClient;
	private boolean listeningToConnect;

	public CometdService(final WebApplication application) {
		_application = application;
		removalListeners = Collections
				.synchronizedMap(new WeakHashMap<String, RemoveListener>());
		listeningToConnect = false;
	}

	public void addChannelListener(final Component component,
			final String channel, final IChannelListener listener) {
		component.add(new CometdBehavior(channel, listener));
	}

	/**
	 * Cometd Specific method to Listen for client removals
	 * 
	 * @param channel
	 * @param listener
	 * @param sess
	 *            Wicket Session you wish to associate with the listener.
	 */
	public void addChannelRemoveListener(final String chnl,
			final RemoveListener listener, final Session sess) {
		if (!listeningToConnect) {
			// getBayeux().getChannel(Channel.META_SUBSCRIBE).subscribe(serviceClient);
			Object serverChannel = getBayeux().getChannel(
					Channel.META_SUBSCRIBE);
			// serverChannel.subscribe(serviceClient);
			serviceClient.addListener(new RemovalForwardingListener());
			listeningToConnect = true;
		}
		removalListeners.put("/" + chnl, new RemovalListener(listener, sess));
	}

	/**
	 * @see #addChannelRemoveListener(String, RemoveListener, Session)
	 */
	public void addChannelRemoveListener(final String chnl,
			final RemoveListener listener) {
		addChannelRemoveListener(chnl, listener, null);
	}

	/**
	 * Implementation of {@link IChannelService#publish(ChannelEvent)}, which
	 * actually sends a cometd event to the client with a "proxy" attribute set
	 * to "true", which in turn triggers a wicket ajax call to get the listener
	 * notified and update the page.
	 * 
	 * @event the event to publish, which will be modify with "proxy" set to
	 *        "true"
	 */
	public void publish(final ChannelEvent event) {
		/*
		 * to avoid using implementation specific events, we set the proxy data
		 * here. this property is used by CometdDefaultBehaviorTemplate.js to
		 * know that the event should actually be converted in a wicket ajax
		 * call to get the actual page refresh
		 */
		event.addData("proxy", "true");
		final String channelId = "/" + event.getChannel();
		Object channel = getBayeux().getChannel(channelId);
		System.err.println(channel);
		// final ClientSessionChannel channel = (ClientSessionChannel)
		// getBayeux().getSession("").getLocalSession().g Channel(channelId);
		// channel.publish(event.getData(), event.getId());
	}

	private final BayeuxServer getBayeux() {
		if (_bayeux == null) {
			initBayeux();
		}

		return _bayeux;
	}

	/**
	 * Initializes the Jetty CometD Bayeux Service to be used.
	 */
	private void initBayeux() {
		final String cfgType = _application.getConfigurationType();
		_bayeux = (BayeuxServer) _application.getServletContext().getAttribute(
				BayeuxServer.ATTRIBUTE);

		/*
		 * if (_bayeux instanceof AbstractBayeux &&
		 * Application.DEVELOPMENT.equalsIgnoreCase(cfgType)) {
		 * ((AbstractBayeux) _bayeux).setLogLevel(2); }
		 * 
		 * if (_bayeux instanceof ContinuationBayeux) { ((ContinuationBayeux)
		 * _bayeux).setJSONCommented(true); }
		 */
		serviceClient = _bayeux.getSession(BAYEUX_CLIENT_PREFIX);
	}

	public Application getApplication() {
		return _application;
	}

}