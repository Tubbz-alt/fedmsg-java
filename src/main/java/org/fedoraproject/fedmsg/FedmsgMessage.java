package org.fedoraproject.fedmsg;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.util.encoders.Base64;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.io.*;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.UUID;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.Security;
import java.security.Signature;
import java.security.SignatureException;

import fj.F;
import fj.data.Either;
import fj.data.IO;

/**
 * An <b>unsigned</b> fedmsg message. You likely never want to send this over
 * the bus (unless you are debugging/developing). Instead you want to send
 * {@link SignedFedmsgMessage} which includes the signature and certificate.
 *
 * This class is immutable, although calls to {@link sign(File, File)} do
 * side effect. This is wrapped in a FunctionalJava IO monad, but this does not
 * imply purity. :'(
 *
 * You can convert this to a {@link SignedFedmsgMessage} by calling
 * {@link sign(File, File)}. Keep in mind that this is wrapped in
 * {@link fj.data.IO}.
 *
 * @author Ricky Elrod
 * @version 2.0.0
 * @see SignedFedmsgMessage
 */
@JsonPropertyOrder(alphabetic=true)
public class FedmsgMessage {
    final private HashMap<String, Object> message;
    final private String topic;
    final private long timestamp;
    final private long i; // What is this?
    final private String msgId;

    public FedmsgMessage(
        final HashMap<String, Object> message,
        final String topic,
        final long timestamp,
        final long i) {
        this.message = message;
        this.topic = topic;
        this.timestamp = timestamp;
        this.i = i;
        this.msgId =
            Integer.toString(Calendar.getInstance().get(Calendar.YEAR)) + "-" +
            UUID.randomUUID().toString();
    }

    protected FedmsgMessage(FedmsgMessage orig) {
        this.message = orig.getMessage();
        this.topic = orig.getTopic();
        this.timestamp = orig.getTimestamp().getTime();
        this.i = orig.getI();
        this.msgId = orig.getMsgId();
    }

    // ew, Object. :-(
    @JsonProperty("msg")
    public final HashMap<String, Object> getMessage() {
        return this.message;
    }

    public final String getTopic() {
        return this.topic;
    }

    public final Date getTimestamp() {
        return new Date(this.timestamp);
    }

    public final long getI() {
        return this.i;
    }

    @JsonProperty("msg_id")
    public final String getMsgId() {
        return this.msgId;
    }

    /**
     * Converts this message into its JSON representation.
     */
    public final ByteArrayOutputStream toJson() throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        ObjectMapper mapper = new ObjectMapper();
        ObjectWriter w = mapper.writer();
        w.with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .writeValue(os, this);
        os.close();
        return os;
    }


    /**
     * Reads a certificate from our .crt file format...
     */
    private final IO<Either<Exception, String>> readCert(final File f) {
        return new IO<Either<Exception, String>>() {
            public Either<Exception,String> run() {
                try {
                    BufferedReader br = new BufferedReader(new FileReader(f));
                    String line;
                    String cert = "";
                    boolean readingCert = false;
                    while ((line = br.readLine()) != null) {
                        if (!readingCert && line.contains("----")) {
                            readingCert = true;
                            cert = cert.concat(line.concat("\n"));
                        } else if (readingCert) {
                            cert = cert.concat(line.concat("\n"));
                        }
                    }
                    br.close();
                    return Either.right(cert);
                } catch (Exception e) {
                    return Either.left(e);
                }
            }
        };
    }

    /**
     * Signs a message.
     *
     * /!\ ugly code (nesting hell) follows.
     */
    public final IO<Either<Exception, SignedFedmsgMessage>> sign(final File cert, final File key) {
        // /!\ Nesting *hell* below
        return readCert(cert).bind(
            new F<Either<Exception, String>, IO<Either<Exception, SignedFedmsgMessage>>>() {
                public IO<Either<Exception, SignedFedmsgMessage>> f(final Either<Exception, String> e) {
                    // Catamorphism. If it's a left, return that exception,
                    // otherwise proceed as normal.
                    return e.either(
                        // Left
                        new F<Exception, IO<Either<Exception, SignedFedmsgMessage>>>() {
                            public IO<Either<Exception, SignedFedmsgMessage>> f(final Exception e) {
                                return new IO<Either<Exception, SignedFedmsgMessage>>() {
                                    public Either<Exception, SignedFedmsgMessage> run() {
                                        return Either.left(e);
                                    }
                                };
                            }
                        },

                        // Right
                        new F<String, IO<Either<Exception, SignedFedmsgMessage>>>() {
                            public IO<Either<Exception, SignedFedmsgMessage>> f(final String c) {
                                return new IO<Either<Exception, SignedFedmsgMessage>>() {
                                    public Either<Exception, SignedFedmsgMessage> run() {
                                        Security.addProvider(new BouncyCastleProvider());
                                        try {
                                            String certString =
                                                new String(Base64.encode(c.getBytes()));

                                            PEMParser keyParser = new PEMParser(new FileReader(key));
                                            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
                                            PrivateKey pkey =
                                                converter.getPrivateKey((PrivateKeyInfo)keyParser.readObject());
                                            Signature signature = Signature.getInstance("SHA1WithRSA", "BC");
                                            signature.initSign(pkey);
                                            signature.update(FedmsgMessage.this.toJson().toString().getBytes());
                                            byte[] signed = signature.sign();
                                            String signatureString = new String(Base64.encode(signed));
                                            return Either.right(
                                                new SignedFedmsgMessage(FedmsgMessage.this, signatureString, certString));
                                        } catch (Exception e) {
                                            return Either.left(e);
                                        }
                                    }
                                };
                            }
                        });
                }
            });
    }
}
