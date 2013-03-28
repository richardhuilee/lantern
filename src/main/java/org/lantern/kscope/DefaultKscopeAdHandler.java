package org.lantern.kscope;

import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.kaleidoscope.BasicTrustGraphAdvertisement;
import org.kaleidoscope.BasicTrustGraphNodeId;
import org.kaleidoscope.RandomRoutingTable;
import org.kaleidoscope.TrustGraphNode;
import org.kaleidoscope.TrustGraphNodeId;
import org.lantern.JsonUtils;
import org.lantern.LanternTrustStore;
import org.lantern.LanternUtils;
import org.lantern.ProxyTracker;
import org.lantern.XmppHandler;
import org.lantern.state.Model;
import org.lantern.state.Notification.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Singleton;

@Singleton
public class DefaultKscopeAdHandler implements KscopeAdHandler {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final XmppHandler xmppHandler;
    
    /**
     * Map of kscope advertisements for which we are awaiting corresponding
     * certificates.
     */
    private final ConcurrentHashMap<String, LanternKscopeAdvertisement> awaitingCerts = 
        new ConcurrentHashMap<String, LanternKscopeAdvertisement>();
    
    private final Set<LanternKscopeAdvertisement> processedAds =
            new HashSet<LanternKscopeAdvertisement>();
    private final ProxyTracker proxyTracker;
    private final LanternTrustStore trustStore;
    private final RandomRoutingTable routingTable;
    private final Model model;
    
    @Inject
    public DefaultKscopeAdHandler(final ProxyTracker proxyTracker,
        final LanternTrustStore trustStore,
        final RandomRoutingTable routingTable,
        final XmppHandler xmppHandler, final Model model) {
        this.proxyTracker = proxyTracker;
        this.trustStore = trustStore;
        this.routingTable = routingTable;
        this.xmppHandler = xmppHandler;
        this.model = model;
    }
    
    @Override
    public boolean handleAd(final String from, 
            final LanternKscopeAdvertisement ad) {
        log.debug("*** got kscope ad from {} for {}", from, ad.getJid());
        if (LanternUtils.isDevMode()) {
            model.addNotification(
                "Received kaleidoscope advertisement from "+ad.getJid(), 
                MessageType.info);
        }
        final LanternKscopeAdvertisement existing = 
            awaitingCerts.put(ad.getJid(), ad);

        if (existing != null) {
            if (existing.equals(ad)) {
                log.debug("Ignoring identical kscope ad - already processed");
                return false;
            }
        }
        // do we want to relay this?
        int inboundTtl = ad.getTtl();
        if(inboundTtl <= 0) {
            log.debug("End of the line for kscope ad for {} from {}.", 
                ad.getJid(), from
            );
            return false;
        }
        TrustGraphNodeId nid = new BasicTrustGraphNodeId(ad.getJid());
        TrustGraphNodeId nextNid = routingTable.getNextHop(nid);
        if(nextNid == null) {
            log.error("Could not relay ad: Node ID not in routing table");
            return false;
        }
        LanternKscopeAdvertisement relayAd = 
            LanternKscopeAdvertisement.makeRelayAd(ad);

        final String relayAdPayload = JsonUtils.jsonify(relayAd);
        final BasicTrustGraphAdvertisement message =
            new BasicTrustGraphAdvertisement(nextNid, relayAdPayload, 
                relayAd.getTtl()
            );

        final TrustGraphNode tgn = 
            new LanternTrustGraphNode(xmppHandler);
        
        tgn.sendAdvertisement(message, nextNid, relayAd.getTtl()); 
        return true;
    }
    
    @Override
    public void onBase64Cert(final String jid, final String base64Cert) {
        try {
            this.trustStore.addBase64Cert(jid, base64Cert);
        } catch (final IOException e) {
            log.error("Could not add cert?", e);
            return;
        }
        
        final LanternKscopeAdvertisement ad = awaitingCerts.remove(jid);
        if (ad != null) {
            if (ad.hasMappedEndpoint()) {
                this.proxyTracker.addProxy(
                        LanternUtils.isa(ad.getAddress(), ad.getPort()));
            } else {
                this.proxyTracker.addJidProxy(LanternUtils.newURI(ad.getJid()));
            }
            
            // Also add the local network advertisement in case they're on
            // the local network.
            this.proxyTracker.addProxy(
                LanternUtils.isa(ad.getLocalAddress(), ad.getLocalPort()));
            processedAds.add(ad);
        } else {
            final URI uri = LanternUtils.newURI(jid);
            if (processedAds.contains(ad) && this.proxyTracker.hasJidProxy(uri)) {
                log.debug("Ignoring cert from peer we already have: {}", uri);
                return;
            } else {
                // This could happen if we negotiated certs in some way other 
                // than in response to a kscope ad, such as for peers from the 
                // controller or just peers from the roster who we haven't 
                // exchanged ads with yet.
                log.debug("No ad for cert?");
                this.proxyTracker.addJidProxy(uri);
            }
        }
    }

}
