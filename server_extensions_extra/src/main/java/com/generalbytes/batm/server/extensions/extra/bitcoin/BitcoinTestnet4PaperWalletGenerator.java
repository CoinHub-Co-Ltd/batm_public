package com.generalbytes.batm.server.extensions.extra.bitcoin;

import com.generalbytes.batm.server.extensions.IExtensionContext;
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

    private final IExtensionContext ctx;

    public BitcoinTestnet4PaperWalletGenerator(IExtensionContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public IPaperWallet generateWallet(String cryptoCurrency, String oneTimePassword, String userLanguage, boolean shouldBeVanity) {
        if (shouldBeVanity) {
            log.warn("Vanity addresses are not supported for BTC testnet paper wallets; generating a random address.");
        }

        NetworkParameters params = TestNet3Params.get();
        ECKey key = new ECKey();
        String address = SegwitAddress.fromKey(params, key).toString();
        String wif = key.getPrivateKeyAsWiF(params);

        log.info("Generated BTC testnet paper wallet address={}", address);

        byte[] content = ctx.createPaperWallet7ZIP(wif, address, oneTimePassword, cryptoCurrency);
        String message = "New " + cryptoCurrency + " wallet " + address
            + " — use your one-time password to open the attachment.";

        return new IPaperWallet() {
            @Override
            public byte[] getContent() {
                return content;
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
            public String getFileExtension() {
                return "zip";
            }

            @Override
            public String getContentType() {
                return "application/zip";
            }

            @Override
            public String getMessage() {
                return message;
            }

            @Override
            public String getCryptoCurrency() {
                return cryptoCurrency;
            }
        };
    }
}
