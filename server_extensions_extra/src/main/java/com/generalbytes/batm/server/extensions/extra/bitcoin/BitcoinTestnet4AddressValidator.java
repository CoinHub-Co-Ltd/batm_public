package com.generalbytes.batm.server.extensions.extra.bitcoin;

import com.generalbytes.batm.server.extensions.ICryptoAddressValidator;
import org.bitcoinj.core.Address;
import org.bitcoinj.params.TestNet3Params;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accepts BTC testnet / testnet4 address encodings ({@code tb1...}, {@code m}/{@code n}, {@code 2...}).
 */
public class BitcoinTestnet4AddressValidator implements ICryptoAddressValidator {
    private static final Logger log = LoggerFactory.getLogger(BitcoinTestnet4AddressValidator.class);

    @Override
    public boolean isAddressValid(String address) {
        if (address == null || address.trim().isEmpty()) {
            return false;
        }
        try {
            Address.fromString(TestNet3Params.get(), address.trim());
            return true;
        } catch (Exception e) {
            log.debug("Invalid BTC testnet address: {}", address);
            return false;
        }
    }

    @Override
    public boolean isPaperWalletSupported() {
        return true;
    }

    @Override
    public boolean mustBeBase58Address() {
        return false; // bech32 tb1... is allowed
    }
}
