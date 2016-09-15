package com.cordova.plugin.android.fingerprintauth;

import org.apache.cordova.CordovaWebView;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaInterface;

import android.annotation.TargetApi;
import android.app.KeyguardManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;

import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.KeyStore;
import java.security.SignatureException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Locale;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;

@TargetApi(23)
public class FingerprintAuth extends CordovaPlugin {

    public static final String TAG = "FingerprintAuth";
    public static String packageName;

    private static final String DIALOG_FRAGMENT_TAG = "FpAuthDialog";
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";

    KeyguardManager mKeyguardManager;
    FingerprintAuthenticationDialogFragment mFragment;
    public static KeyStore mKeyStore;
    public static KeyGenerator mKeyGenerator;
    public static KeyPairGenerator mKeyPairGenerator;
    public static Cipher mCipher;
    public static Signature mSignature;
    private FingerprintManager mFingerPrintManager;

    public static CallbackContext mCallbackContext;
    public static PluginResult mPluginResult;

    /**
     * Alias for our key in the Android Key Store
     */
    private static String mClientId;
    /**
     * Used to encrypt token
     */
    private static String mClientSecret;

    /**
     * Options
     */
    private static boolean mDisableBackup = false;
    private String mLangCode = "en_US";

    /**
     * Constructor.
     */
    public FingerprintAuth() {
    }

    /**
     * Sets the context of the Command. This can then be used to do things like
     * get file paths associated with the Activity.
     *
     * @param cordova The context of the main Activity.
     * @param webView The CordovaWebView Cordova is running in.
     */

    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        Log.v(TAG, "Init FingerprintAuth");
        packageName = cordova.getActivity().getApplicationContext().getPackageName();
        mPluginResult = new PluginResult(PluginResult.Status.NO_RESULT);

        if (android.os.Build.VERSION.SDK_INT < 23) {
            return;
        }

        mKeyguardManager = cordova.getActivity().getSystemService(KeyguardManager.class);
        mFingerPrintManager = cordova.getActivity().getApplicationContext()
                .getSystemService(FingerprintManager.class);

        try {
            mKeyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
            mKeyStore = KeyStore.getInstance(ANDROID_KEY_STORE);

        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to get an instance of KeyGenerator", e);
        } catch (NoSuchProviderException e) {
            throw new RuntimeException("Failed to get an instance of KeyGenerator", e);
        } catch (KeyStoreException e) {
            throw new RuntimeException("Failed to get an instance of KeyStore", e);
        }

        try {
            mKeyPairGenerator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEY_STORE);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to get an instance of KeyPairGenerator", e);
        } catch (NoSuchProviderException e) {
            throw new RuntimeException("Failed to get an instance of KeyPairGenerator", e);
        }

        try {
            mCipher = Cipher.getInstance(KeyProperties.KEY_ALGORITHM_AES + "/"
                    + KeyProperties.BLOCK_MODE_CBC + "/"
                    + KeyProperties.ENCRYPTION_PADDING_PKCS7);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to get an instance of Cipher", e);
        } catch (NoSuchPaddingException e) {
            throw new RuntimeException("Failed to get an instance of Cipher", e);
        }

        try {
            mSignature = Signature.getInstance("SHA256withRSA");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to get an instance of Signature", e);
        }
    }

    /**
     * Executes the request and returns PluginResult.
     *
     * @param action          The action to execute.
     * @param args            JSONArry of arguments for the plugin.
     * @param callbackContext The callback id used when calling back into JavaScript.
     * @return A PluginResult object with a status and message.
     */
    public boolean execute(final String action,
                           JSONArray args,
                           CallbackContext callbackContext) throws JSONException {
        mCallbackContext = callbackContext;
        Log.v(TAG, "FingerprintAuth action: " + action);
        if (android.os.Build.VERSION.SDK_INT < 23) {
            Log.e(TAG, "minimum SDK version 23 required");
            mPluginResult = new PluginResult(PluginResult.Status.ERROR);
            mCallbackContext.error("minimum SDK version 23 required");
            mCallbackContext.sendPluginResult(mPluginResult);
            return true;
        }

        final JSONObject arg_object = args.getJSONObject(0);
        if (action.equals("setup")) {
            if (!arg_object.has("clientId")) {
                mPluginResult = new PluginResult(PluginResult.Status.ERROR);
                mCallbackContext.error("Missing required parameters");
                mCallbackContext.sendPluginResult(mPluginResult);
                return true;
            }
            mClientId = arg_object.getString("clientId");
            if (isFingerprintAuthAvailable()) {
                if (!createKeyPair()) {
                    mCallbackContext.sendPluginResult(mPluginResult);
                }
                if (!initSignature()) {
                    mPluginResult = new PluginResult(PluginResult.Status.ERROR);
                    mCallbackContext.error("Failed to init Signature");
                    mCallbackContext.sendPluginResult(mPluginResult);
                } else {
                    cordova.getActivity().runOnUiThread(new Runnable() {
                        public void run() {
                            // Set up the crypto object for later. The object will be authenticated by use
                            // of the fingerprint.
                            mFragment = new FingerprintAuthenticationDialogFragment();
                            Bundle bundle = new Bundle();
                            bundle.putBoolean("disableBackup", mDisableBackup);
                            bundle.putBoolean("setupAsymmetric", true);
                            mFragment.setArguments(bundle);
                            mFragment.setCancelable(false);
                            // Show the fingerprint dialog. The user has the option to use the fingerprint with
                            // crypto, or you can fall back to using a server-side verified password.
                            mFragment.setCryptoObject(new FingerprintManager.CryptoObject(mSignature));
                            mFragment.show(cordova.getActivity()
                                    .getFragmentManager(), DIALOG_FRAGMENT_TAG);
                        }
                    });
                    mPluginResult.setKeepCallback(true);
                }
                return true;
            } else {
                mPluginResult = new PluginResult(PluginResult.Status.ERROR);
                mCallbackContext.error("Fingerprint authentication not available");
                mCallbackContext.sendPluginResult(mPluginResult);
            }
        }

        if (action.equals("authenticate")) {
            if (!arg_object.has("clientId") || !arg_object.has("clientSecret")) {
                mPluginResult = new PluginResult(PluginResult.Status.ERROR);
                mCallbackContext.error("Missing required parameters");
                mCallbackContext.sendPluginResult(mPluginResult);
                return true;
            }
            mClientId = arg_object.getString("clientId");
            mClientSecret = arg_object.getString("clientSecret");
            if (arg_object.has("disableBackup")) {
                mDisableBackup = arg_object.getBoolean("disableBackup");
            }
            if (arg_object.has("locale")) {
                mLangCode = arg_object.getString("locale");
                Log.d(TAG, "Change language to locale: " + mLangCode);
            }
            // Set language
            Resources res = cordova.getActivity().getResources();
            // Change locale settings in the app.
            DisplayMetrics dm = res.getDisplayMetrics();
            Configuration conf = res.getConfiguration();
            conf.locale = new Locale(mLangCode.toLowerCase());
            res.updateConfiguration(conf, dm);

            if (isFingerprintAuthAvailable()) {
                if (!arg_object.has("asymmetricEncryption") ||
                        !arg_object.getBoolean("asymmetricEncryption")) {
                    SecretKey key = getSecretKey();
                    boolean isCipherInit = true;
                    if (key == null) {
                        if (createKey()) {
                            key = getSecretKey();
                        }
                    }
                    if (key != null && !initCipher()) {
                        isCipherInit = false;
                    }
                    if (key != null) {
                        cordova.getActivity().runOnUiThread(new Runnable() {
                            public void run() {
                                // Set up the crypto object for later. The object will be authenticated by use
                                // of the fingerprint.
                                mFragment = new FingerprintAuthenticationDialogFragment();
                                Bundle bundle = new Bundle();
                                bundle.putBoolean("disableBackup", mDisableBackup);
                                mFragment.setArguments(bundle);
                                if (initCipher()) {
                                    mFragment.setCancelable(false);
                                    // Show the fingerprint dialog. The user has the option to use the fingerprint with
                                    // crypto, or you can fall back to using a server-side verified password.
                                    mFragment.setCryptoObject(new FingerprintManager.CryptoObject(mCipher));
                                    mFragment.show(cordova.getActivity()
                                            .getFragmentManager(), DIALOG_FRAGMENT_TAG);
                                } else {
                                    if (!mDisableBackup) {
                                        // This happens if the lock screen has been disabled or or a fingerprint got
                                        // enrolled. Thus show the dialog to authenticate with their password
                                        mFragment.setCryptoObject(new FingerprintManager
                                                .CryptoObject(mCipher));
                                        mFragment.setStage(FingerprintAuthenticationDialogFragment
                                                .Stage.NEW_FINGERPRINT_ENROLLED);
                                        mFragment.show(cordova.getActivity().getFragmentManager(),
                                                DIALOG_FRAGMENT_TAG);
                                    } else {
                                        mCallbackContext.error("Failed to init Cipher and backup disabled.");
                                        mPluginResult = new PluginResult(PluginResult.Status.ERROR);
                                        mCallbackContext.sendPluginResult(mPluginResult);
                                    }
                                }
                            }
                        });
                        mPluginResult.setKeepCallback(true);
                    } else {
                        mCallbackContext.sendPluginResult(mPluginResult);
                    }
                } else {
                    PrivateKey key = getPrivateKey();
                    if (key == null) {
                        mPluginResult = new PluginResult(PluginResult.Status.ERROR);
                        mCallbackContext.error("There is no generated key pair for this clientId");
                        mCallbackContext.sendPluginResult(mPluginResult);
                    }
                    if (key != null && !initSignature()) {
                        mPluginResult = new PluginResult(PluginResult.Status.ERROR);
                        mCallbackContext.error("Failes to init Signature");
                        mCallbackContext.sendPluginResult(mPluginResult);
                    } else {
                        cordova.getActivity().runOnUiThread(new Runnable() {
                            public void run() {
                                // Set up the crypto object for later. The object will be authenticated by use
                                // of the fingerprint.
                                mFragment = new FingerprintAuthenticationDialogFragment();
                                Bundle bundle = new Bundle();
                                bundle.putBoolean("disableBackup", mDisableBackup);
                                bundle.putBoolean("asymmetricEncryption", true);
                                mFragment.setArguments(bundle);
                                mFragment.setCancelable(false);
                                // Show the fingerprint dialog. The user has the option to use the fingerprint with
                                // crypto, or you can fall back to using a server-side verified password.
                                mFragment.setCryptoObject(new FingerprintManager.CryptoObject(mSignature));
                                mFragment.show(cordova.getActivity()
                                        .getFragmentManager(), DIALOG_FRAGMENT_TAG);
                            }
                        });
                        mPluginResult.setKeepCallback(true);
                    }
                }
            } else {
                mPluginResult = new PluginResult(PluginResult.Status.ERROR);
                mCallbackContext.error("Fingerprint authentication not available");
                mCallbackContext.sendPluginResult(mPluginResult);
            }
            return true;
        } else if (action.equals("availability")) {
            JSONObject resultJson = new JSONObject();
            resultJson.put("isAvailable", isFingerprintAuthAvailable());
            resultJson.put("isHardwareDetected", mFingerPrintManager.isHardwareDetected());
            resultJson.put("hasEnrolledFingerprints", mFingerPrintManager.hasEnrolledFingerprints());
            mPluginResult = new PluginResult(PluginResult.Status.OK);
            mCallbackContext.success(resultJson);
            mCallbackContext.sendPluginResult(mPluginResult);
            return true;
        }
        return false;
    }

    private boolean isFingerprintAuthAvailable() {
        return mFingerPrintManager.isHardwareDetected()
                && mFingerPrintManager.hasEnrolledFingerprints();
    }

    /**
     * Initialize the {@link Cipher} instance with the created key in the {@link #createKey()}
     * method.
     *
     * @return {@code true} if initialization is successful, {@code false} if the lock screen has
     * been disabled or reset after the key was generated, or if a fingerprint got enrolled after
     * the key was generated.
     */
    private static boolean initCipher() {
        boolean initCipher = false;
        String errorMessage = "";
        String initCipherExceptionErrorPrefix = "Failed to init Cipher: ";
        try {
            SecretKey key = getSecretKey();
            mCipher.init(Cipher.ENCRYPT_MODE, key);
            initCipher = true;
        } catch (InvalidKeyException e) {
            errorMessage = initCipherExceptionErrorPrefix
                    + "InvalidKeyException: " + e.toString();
        }
        if (!initCipher) {
            Log.e(TAG, errorMessage);
        }
        return initCipher;
    }

    /**
     * Initialize the {@link Signature} instance with the created private key in the
     * {@link #createKeyPair()} method.
     *
     * @return {@code true} if initialization is successful, {@code false} if the lock screen has
     * been disabled or reset after the key was generated, or if a fingerprint got enrolled after
     * the key was generated.
     */
    private static boolean initSignature() {
        boolean initSignature = false;
        String errorMessage = "";
        String initSignatureExceptionErrorPrefix = "Failed to init Signature: ";
        try {
            PrivateKey key = getPrivateKey();
            mSignature.initSign(key);
            initSignature = true;
        } catch (InvalidKeyException e) {
            errorMessage = initSignatureExceptionErrorPrefix
                    + "InvalidKeyException: " + e.toString();
        }
        if (!initSignature) {
            Log.e(TAG, errorMessage);
        }
        return initSignature;
    }

    private static SecretKey getSecretKey() {
        String errorMessage = "";
        String getSecretKeyExceptionErrorPrefix = "Failed to get SecretKey from KeyStore: ";
        SecretKey key = null;
        try {
            mKeyStore.load(null);
            key = (SecretKey) mKeyStore.getKey(mClientId, null);
        } catch (KeyStoreException e) {
            errorMessage = getSecretKeyExceptionErrorPrefix
                    + "KeyStoreException: " + e.toString();
        } catch (CertificateException e) {
            errorMessage = getSecretKeyExceptionErrorPrefix
                    + "CertificateException: " + e.toString();
        } catch (UnrecoverableKeyException e) {
            errorMessage = getSecretKeyExceptionErrorPrefix
                    + "UnrecoverableKeyException: " + e.toString();
        } catch (IOException e) {
            errorMessage = getSecretKeyExceptionErrorPrefix
                    + "IOException: " + e.toString();
        } catch (NoSuchAlgorithmException e) {
            errorMessage = getSecretKeyExceptionErrorPrefix
                    + "NoSuchAlgorithmException: " + e.toString();
        }
        if (key == null) {
            Log.e(TAG, errorMessage);
        }
        return key;
    }

    /**
     * Creates a symmetric key in the Android Key Store which can only be used after the user has
     * authenticated with fingerprint.
     */
    public static boolean createKey() {
        String errorMessage = "";
        String createKeyExceptionErrorPrefix = "Failed to create key: ";
        boolean isKeyCreated = false;
        // The enrolling flow for fingerprint. This is where you ask the user to set up fingerprint
        // for your flow. Use of keys is necessary if you need to know if the set of
        // enrolled fingerprints has changed.
        try {
            mKeyStore.load(null);
            // Set the alias of the entry in Android KeyStore where the key will appear
            // and the constrains (purposes) in the constructor of the Builder
            mKeyGenerator.init(new KeyGenParameterSpec.Builder(mClientId,
                    KeyProperties.PURPOSE_ENCRYPT |
                            KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                    // Require the user to authenticate with a fingerprint to authorize every use
                    // of the key
                    .setUserAuthenticationRequired(true)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                    .build());
            mKeyGenerator.generateKey();
            isKeyCreated = true;
        } catch (NoSuchAlgorithmException e) {
            errorMessage = createKeyExceptionErrorPrefix
                    + "NoSuchAlgorithmException: " + e.toString();
        } catch (InvalidAlgorithmParameterException e) {
            errorMessage = createKeyExceptionErrorPrefix
                    + "InvalidAlgorithmParameterException: " + e.toString();
        } catch (CertificateException e) {
            errorMessage = createKeyExceptionErrorPrefix
                    + "CertificateException: " + e.toString();
        } catch (IOException e) {
            errorMessage = createKeyExceptionErrorPrefix
                    + "IOException: " + e.toString();
        }
        if (!isKeyCreated) {
            Log.e(TAG, errorMessage);
            setPluginResultError(errorMessage);
        }
        return isKeyCreated;
    }

    private static PrivateKey getPrivateKey() {
        String errorMessage = "";
        String getPrivateKeyExceptionErrorPrefix = "Failed to get PrivateKey from KeyStore: ";
        PrivateKey key = null;
        try {
            mKeyStore.load(null);
            key = (PrivateKey) mKeyStore.getKey(mClientId, null);
        } catch (KeyStoreException e) {
            errorMessage = getPrivateKeyExceptionErrorPrefix
                    + "KeyStoreException: " + e.toString();
        } catch (CertificateException e) {
            errorMessage = getPrivateKeyExceptionErrorPrefix
                    + "CertificateException: " + e.toString();
        } catch (UnrecoverableKeyException e) {
            errorMessage = getPrivateKeyExceptionErrorPrefix
                    + "UnrecoverableKeyException: " + e.toString();
        } catch (IOException e) {
            errorMessage = getPrivateKeyExceptionErrorPrefix
                    + "IOException: " + e.toString();
        } catch (NoSuchAlgorithmException e) {
            errorMessage = getPrivateKeyExceptionErrorPrefix
                    + "NoSuchAlgorithmException: " + e.toString();
        }
        if (key == null) {
            Log.e(TAG, errorMessage);
        }
        return key;
    }

    private static PublicKey getPublicKey() {
        String errorMessage = "";
        String getPublicKeyExceptionErrorPrefix = "Failed to get PublicKey from KeyStore: ";
        PublicKey key = null;
        try {
            mKeyStore.load(null);
            key = (PublicKey) mKeyStore.getCertificate(mClientId).getPublicKey();
        } catch (KeyStoreException e) {
            errorMessage = getPublicKeyExceptionErrorPrefix
                    + "KeyStoreException: " + e.toString();
        } catch (CertificateException e) {
            errorMessage = getPublicKeyExceptionErrorPrefix
                    + "CertificateException: " + e.toString();
        } catch (IOException e) {
            errorMessage = getPublicKeyExceptionErrorPrefix
                    + "IOException: " + e.toString();
        } catch (NoSuchAlgorithmException e) {
            errorMessage = getPublicKeyExceptionErrorPrefix
                    + "NoSuchAlgorithmException: " + e.toString();
        }
        if (key == null) {
            Log.e(TAG, errorMessage);
        }
        return key;
    }

    /**
     * Creates an asymmetric key pair in the Android Key Store which can only be used after the user has
     * authenticated with fingerprint.
     */
    public static boolean createKeyPair() {
        String errorMessage = "";
        String createKeyPairExceptionErrorPrefix = "Failed to create key pair: ";
        boolean isKeyPairCreated = false;
        // The enrolling flow for fingerprint. This is where you ask the user to set up fingerprint
        // for your flow. Use of keys is necessary if you need to know if the set of
        // enrolled fingerprints has changed.
        try {
            mKeyStore.load(null);
            // Set the alias of the entry in Android KeyStore where the key will appear
            // and the constrains (purposes) in the constructor of the Builder
            mKeyPairGenerator.initialize(new KeyGenParameterSpec.Builder(mClientId,
                    KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .build());
            mKeyPairGenerator.generateKeyPair();
            isKeyPairCreated = true;
        } catch (NoSuchAlgorithmException e) {
            errorMessage = createKeyPairExceptionErrorPrefix
                    + "NoSuchAlgorithmException: " + e.toString();
        } catch (InvalidAlgorithmParameterException e) {
            errorMessage = createKeyPairExceptionErrorPrefix
                    + "InvalidAlgorithmParameterException: " + e.toString();
        } catch (CertificateException e) {
            errorMessage = createKeyPairExceptionErrorPrefix
                    + "CertificateException: " + e.toString();
        } catch (IOException e) {
            errorMessage = createKeyPairExceptionErrorPrefix
                    + "IOException: " + e.toString();
        }
        if (!isKeyPairCreated) {
            Log.e(TAG, errorMessage);
            setPluginResultError(errorMessage);
        }
        return isKeyPairCreated;
    }

    public static void onAuthenticated(boolean withFingerprint) {
        JSONObject resultJson = new JSONObject();
        String errorMessage = "";
        boolean createdResultJson = false;
        try {

            if (withFingerprint) {
                // If the user has authenticated with fingerprint, verify that using cryptography and
                // then return the encrypted token
                byte[] encrypted = tryEncrypt();
                resultJson.put("withFingerprint", Base64.encodeToString(encrypted, 0 /* flags */));
            } else {
                // Authentication happened with backup password.
                resultJson.put("withPassword", true);

                // if failed to init cipher because of InvalidKeyException, create new key
                if (!initCipher()) {
                    createKey();
                }
            }
            createdResultJson = true;
        } catch (BadPaddingException e) {
            errorMessage = "Failed to encrypt the data with the generated key:" +
                    " BadPaddingException:  " + e.getMessage();
            Log.e(TAG, errorMessage);
        } catch (IllegalBlockSizeException e) {
            errorMessage = "Failed to encrypt the data with the generated key: " +
                    "IllegalBlockSizeException: " + e.getMessage();
            Log.e(TAG, errorMessage);
        } catch (JSONException e) {
            errorMessage = "Failed to set resultJson key value pair: " + e.getMessage();
            Log.e(TAG, errorMessage);
        }

        if (createdResultJson) {
            mCallbackContext.success(resultJson);
            mPluginResult = new PluginResult(PluginResult.Status.OK);
        } else {
            mCallbackContext.error(errorMessage);
            mPluginResult = new PluginResult(PluginResult.Status.ERROR);
        }
        mCallbackContext.sendPluginResult(mPluginResult);
    }

    public static void onAuthenticatedWithAsymmetricKeys() {
        JSONObject resultJson = new JSONObject();
        String errorMessage = "";
        boolean createdResultJson = false;
        try {
            // If the user has authenticated with fingerprint, verify that using cryptography and
            // then return the encrypted token
            byte[] encrypted = tryEncryptBySignature();
            resultJson.put("response", Base64.encodeToString(encrypted, 0 /* flags */));
            createdResultJson = true;
        } catch (SignatureException e) {
            errorMessage = "Failed to encrypt the data with the generated key:" +
                    " SignatureException:  " + e.getMessage();
            Log.e(TAG, errorMessage);
        } catch (JSONException e) {
            errorMessage = "Failed to set resultJson key value pair: " + e.getMessage();
            Log.e(TAG, errorMessage);
        }

        if (createdResultJson) {
            mCallbackContext.success(resultJson);
            mPluginResult = new PluginResult(PluginResult.Status.OK);
        } else {
            mCallbackContext.error(errorMessage);
            mPluginResult = new PluginResult(PluginResult.Status.ERROR);
        }
        mCallbackContext.sendPluginResult(mPluginResult);
    }

    public static void onSuccessfulAsymmetricSetup() {
        JSONObject resultJson = new JSONObject();
        String errorMessage = "";
        boolean createdResultJson = false;
        try {
            PublicKey publicKey = getPublicKey();
            if (publicKey != null) {
                resultJson.put("publicKey", Base64.encodeToString(publicKey.getEncoded(), 0 /* flags */));
                createdResultJson = true;
            }
        } catch (JSONException e) {
            errorMessage = "Failed to set resultJson key value pair: " + e.getMessage();
            Log.e(TAG, errorMessage);
        }

        if (createdResultJson) {
            mCallbackContext.success(resultJson);
            mPluginResult = new PluginResult(PluginResult.Status.OK);
        } else {
            mCallbackContext.error(errorMessage);
            mPluginResult = new PluginResult(PluginResult.Status.ERROR);
        }
        mCallbackContext.sendPluginResult(mPluginResult);
    }

    public static void onCancelled() {
        mCallbackContext.error("Cancelled");
    }

    /**
     * Tries to encrypt some data with the generated key in {@link #createKey} which is
     * only works if the user has just authenticated via fingerprint.
     */
    private static byte[] tryEncrypt() throws BadPaddingException, IllegalBlockSizeException {
        return mCipher.doFinal(mClientSecret.getBytes());
    }

    /**
     * Tries to encrypt some data with the generated private key in {@link #createKeyPair} which is
     * only works if the user has just authenticated via fingerprint.
     */
    private static byte[] tryEncryptBySignature() throws SignatureException {
        mSignature.update(mClientSecret.getBytes());
        return mSignature.sign();
    }

    public static boolean setPluginResultError(String errorMessage) {
        mCallbackContext.error(errorMessage);
        mPluginResult = new PluginResult(PluginResult.Status.ERROR);
        return false;
    }
}