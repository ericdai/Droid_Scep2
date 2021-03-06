package org.jscep.client;

import java.math.BigInteger;
import java.security.cert.CertStore;
import java.security.cert.CertStoreException;
import java.security.cert.Certificate;

import java.security.cert.X509Certificate;
import java.util.Collection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.x509.X509CertStoreSelector;

import android.util.Log;

/**
 * This class is used for storing CA and RA certificates.
 */
public final class CertStoreInspector {
	private static final Logger LOGGER = LoggerFactory.getLogger(CertStoreInspector.class);
	private static final int KEY_USAGE_LENGTH = 9;
	private static final int DIGITAL_SIGNATURE = 0;
	private static final int KEY_ENCIPHERMENT = 2;
	private static final int DATA_ENCIPHERMENT = 2;//should this be 2?

	private final X509Certificate verifier;
	private final X509Certificate encrypter;
	private final X509Certificate issuer;

	private CertStoreInspector(X509Certificate verifier, X509Certificate encrypter, X509Certificate issuer) {
		this.verifier = verifier;
		this.encrypter = encrypter;
		this.issuer = issuer;
	}

	/**
	 * Returns the verifier certificate.
	 * 
	 * @return the verifier certificate.
	 */
	public X509Certificate getSigner() {
		return verifier;
	}

	/**
	 * Returns the encrypter certificate.
	 * 
	 * @return the encrypter certificate.
	 */
	public X509Certificate getRecipient() {
		return encrypter;
	}

	/**
	 * Returns the issuer certificate.
	 * 
	 * @return the issuer certificate.
	 */
	public X509Certificate getIssuer() {
		return issuer;
	}

	/**
	 * Inspects the given CertStore to extract an Authorities instance.
	 * <p>
	 * This method will inspect the given CertStore with pre-configured
	 * selectors to match RA certificates for encryption and verification, plus
	 * the issuing CA certificate.
	 * <p>
	 * If the CertStore only contains a single CA certificate, that certificate
	 * will be used for all three roles.
	 * 
	 * @param store
	 *            the store to inspect.
	 * @return the Authorities instance.
	 */
	public static CertStoreInspector inspect(final CertStore store) {
		try {
			Collection<? extends Certificate> certs = store.getCertificates(null);
			LOGGER.debug("CertStore contains {} certificate(s):", certs.size());
			int i = 0;
			for (Certificate cert : certs) {
				X509Certificate x509 = (X509Certificate) cert;
				LOGGER.debug("{}. '[issuer={}; serial={}]'", new Object[] {++i, x509.getIssuerDN(), x509.getSerialNumber()});
			}

			//get the CEP encryption cert:
			X509Certificate encryption = selectEncryptionCertificate(store);			
			LOGGER.debug("Using [issuer={}; serial={}] for message encryption",encryption.getIssuerDN(), encryption.getSerialNumber());
			//TODO: update this: //GORDON: convert cert serial# to HEX from BI //output to LogCat vs log..
			BigInteger bi = new BigInteger(encryption.getSerialNumber().toString());
			Log.i("MainActivity", "Using cert for message encryption: " + " " + encryption.getSubjectDN().toString() + " " + bi.toString(16));
			
			X509Certificate signing = selectSigner(store);
			LOGGER.debug("Using [issuer={}; serial={}] for message verification",
					signing.getIssuerDN(), encryption.getSerialNumber());

			
			X509Certificate issuer = selectIssuer(store);
			LOGGER.debug("Using [issuer={}; serial={}] for issuer", signing.getIssuerDN(), encryption.getSerialNumber());

			return new CertStoreInspector(signing, encryption, issuer);
		} catch (CertStoreException e) {
			throw new RuntimeException(e);
		}
	}

	private static X509Certificate selectIssuer(CertStore store)
			throws CertStoreException {
		LOGGER.debug("Selecting issuer certificate");

		LOGGER.debug("Selecting certificate with basicConstraints");
		return getCaCertificate(store);
	}

	private static X509Certificate selectSigner(CertStore store)
			throws CertStoreException {
		LOGGER.debug("Selecting verifier certificate");
		boolean[] keyUsage = new boolean[KEY_USAGE_LENGTH];
		keyUsage[DIGITAL_SIGNATURE] = true;
		X509CertStoreSelector signingSelector = new X509CertStoreSelector();
		signingSelector.setBasicConstraints(-2);
		signingSelector.setKeyUsage(keyUsage);

		LOGGER.debug("Selecting certificate with digitalSignature keyUsage");
		Collection<? extends Certificate> certs = store
				.getCertificates(signingSelector);
		if (certs.size() > 0) {
			LOGGER.debug("Found {} certificate(s) with digitalSignature keyUsage", certs.size());
			return (X509Certificate) certs.iterator().next();
		} else {
			LOGGER.debug("No certificates found.  Falling back to CA certificate");
			return getCaCertificate(store);
		}
	}

	private static X509Certificate getCaCertificate(CertStore store)
			throws CertStoreException {
		X509CertStoreSelector selector = new X509CertStoreSelector();
//		selector.setKeyUsage(new boolean[KEY_USAGE_LENGTH]);
		selector.setBasicConstraints(0);

		Collection<? extends Certificate> certs = store.getCertificates(selector);
		if (certs.size() > 0) {
			LOGGER.debug("Found {} certificate(s) with basicConstraints", certs.size());
			return (X509Certificate) certs.iterator().next();
		} else {
			throw new RuntimeException(
					"No suitable certificate for verification");
		}
	}

	private static X509Certificate selectEncryptionCertificate(CertStore store)
			throws CertStoreException {
		LOGGER.debug("Selecting encryption certificate");
		boolean[] keyUsage = new boolean[KEY_USAGE_LENGTH];
		keyUsage[DATA_ENCIPHERMENT] = true;
		X509CertStoreSelector signingSelector = new X509CertStoreSelector();
		
		//TODO: Gordon Changed this to disable PathLen check
		signingSelector.setBasicConstraints(-1);
		signingSelector.setKeyUsage(keyUsage);

		LOGGER.debug("Selecting certificate with keyEncipherment keyUsage");
		Collection<? extends Certificate> certs = store
				.getCertificates(signingSelector);
		if (certs.size() > 0) {
			LOGGER.debug("Found {} certificate(s) with keyEncipherment keyUsage", certs.size());
			return (X509Certificate) certs.iterator().next();
		}

		LOGGER.debug("No certificates found.  Selecting certificate with dataEncipherment keyUsage");
		keyUsage = new boolean[KEY_USAGE_LENGTH];
		
		//TODO: Change this back
		keyUsage[KEY_ENCIPHERMENT] = true;
		signingSelector.setKeyUsage(keyUsage);

		certs = store.getCertificates(signingSelector);
		if (certs.size() > 0) {
			LOGGER.debug("Found {} certificate(s) with dataEncipherment keyUsage", certs.size());
			return (X509Certificate) certs.iterator().next();
		}

		LOGGER.debug("No certificates found.  Falling back to CA certificate");
		return getCaCertificate(store);
	}
}
