package com.generalbytes.batm.server.extensions.extra.bitcoin;

import com.generalbytes.batm.server.extensions.IPaperWallet;
import com.generalbytes.batm.server.extensions.IPaperWalletGenerator;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.SegwitAddress;
import org.bitcoinj.params.TestNet3Params;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class BitcoinTestnet4PaperWalletGenerator implements IPaperWalletGenerator {
    private static final Logger log = LoggerFactory.getLogger(BitcoinTestnet4PaperWalletGenerator.class);

    @Override
    public String toString() {
        return "BitcoinTestnet4PaperWalletGenerator@" + Integer.toHexString(System.identityHashCode(this));
    }

    @Override
    public IPaperWallet generateWallet(String cryptoCurrency, String oneTimePassword, String userLanguage, boolean shouldBeVanity) {
        log.info("Seiki generateWallet called for cryptoCurrency={}, oneTimePassword={}, userLanguage={}, shouldBeVanity={}", cryptoCurrency, oneTimePassword, userLanguage, shouldBeVanity);
        if (shouldBeVanity) {
            log.warn("Vanity addresses are not supported for BTC testnet4 paper wallets; generating a random address.");
        }
        log.info("Seiki reached paper wallet generation code");

        NetworkParameters params = TestNet3Params.get();
        ECKey key = new ECKey();
        String address = SegwitAddress.fromKey(params, key).toString();
        String wif = key.getPrivateKeyAsWiF(params);

        log.info("Generated BTC testnet4 paper wallet address={}", address);

        return new IPaperWallet() {
            @Override
            public byte[] getContent() {
                return new byte[0];
            }

            @Override
            public String getAddress() {
                return address;
            }

            @Override
            public String getPrivateKey() {
                return wif;
            }

            @Override
            public String getMessage() {
                return null;
            }

            @Override
            public String getContentType() {
                return null;
            }

            @Override
            public String getFileExtension() {
                return null;
            }

            @Override
            public String getCryptoCurrency() {
                return cryptoCurrency;
            }
        };
    }
}
