/*************************************************************************************
 * CoinHub notification listener — dispenser cassette hardware in/out events.
 ************************************************************************************/
package com.generalbytes.batm.server.extensions.extra.ryocoin;

import com.generalbytes.batm.server.extensions.INotificationListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Receives BATM dispenser cassette manipulation events and updates {@link CoinHubDispenserCassetteTracker}.
 */
final class CoinHubNotificationListener implements INotificationListener {

    private static final Logger log = LoggerFactory.getLogger(CoinHubNotificationListener.class);
    private final CoinHubDispenserCassetteTracker tracker = CoinHubDispenserCassetteTracker.getInstance();

    @Override
    public void dispenserCassetteOut(String terminalSerialNumber, String cassetteInfo) {
        log.info("dispenserCassetteOut notification: serial={}, info={}", terminalSerialNumber, cassetteInfo);
        tracker.markOut(terminalSerialNumber, cassetteInfo);
    }

    @Override
    public void dispenserCassetteIn(String terminalSerialNumber, String cassetteInfo) {
        log.info("dispenserCassetteIn notification: serial={}, info={}", terminalSerialNumber, cassetteInfo);
        tracker.markIn(terminalSerialNumber, cassetteInfo);
    }
}
