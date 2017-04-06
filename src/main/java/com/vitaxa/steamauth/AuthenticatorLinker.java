package com.vitaxa.steamauth;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.lang.reflect.Type;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import com.vitaxa.steamauth.APIEndpoints;
import com.vitaxa.steamauth.helper.IOHelper;
import com.vitaxa.steamauth.http.HttpMethod;
import com.vitaxa.steamauth.http.HttpParameters;
import org.apache.http.client.CookieStore;
import org.apache.http.impl.client.BasicCookieStore;

public class AuthenticatorLinker {
    /**
     * Set to register a new phone number when linking.
     * If a phone number is not set on the account, this must be set.
     * If a phone number is set on the account, this must be null.
     */
    public String phoneNumber = null;

    /**
     * Randomly-generated device ID. Should only be generated once per linker.
     */
    public String deviceID;

    /**
     * After the initial link step, if successful, this will be the SteamGuard data for the account.
     * PLEASE save this somewhere after generating it; it's vital data.
     */
    public SteamGuardAccount linkedAccount;

    /**
     * True if the authenticator has been fully finalized.
     */
    public boolean finalized = false;

    private SessionData session;
    private CookieStore cookieStore;

    public AuthenticatorLinker(SessionData session) {
        this.session = session;
        this.deviceID = generateDeviceID();

        this.cookieStore = new BasicCookieStore();
        session.addCookies(this.cookieStore);

        SteamWeb.setCookieStore(cookieStore);
    }

    public LinkResult addAuthenticator() {
        boolean hasPhone = hasPhoneAttached();
        if (hasPhone && phoneNumber != null)
            return LinkResult.MUST_REMOVE_PHONE_NUMBER;
        if (!hasPhone && phoneNumber == null)
            return LinkResult.MUST_PROVIDE_PHONE_NUMBER;

        if (!hasPhone) {
            if (!addPhoneNumber()) {
                return LinkResult.GENERAL_FAILURE;
            }
        }
        Map<String, String> postData = new HashMap<>();
        postData.put("access_token", session.getOAuthToken());
        postData.put("steamid", String.valueOf(session.getSteamID()));
        postData.put("authenticator_type", "1");
        postData.put("device_identifier", deviceID);
        postData.put("sms_phone_id", "1");

        String response = SteamWeb.mobileLoginRequest(APIEndpoints.STEAMAPI_BASE + "/ITwoFactorService/AddAuthenticator/v0001",
                new HttpParameters(postData, HttpMethod.POST));
        if (response == null) return LinkResult.GENERAL_FAILURE;

        AddAuthenticatorResponse addAuthenticatorResponse = new Gson().fromJson(response, AddAuthenticatorResponse.class);

        if (addAuthenticatorResponse == null || addAuthenticatorResponse.response == null) {
            return LinkResult.GENERAL_FAILURE;
        }
        if (addAuthenticatorResponse.response.getStatus() == 29) {
            return LinkResult.AUTHENTICATOR_PRESENT;
        }

        if (addAuthenticatorResponse.response.getStatus() != 1) {
            return LinkResult.GENERAL_FAILURE;
        }

        linkedAccount = addAuthenticatorResponse.response;
        linkedAccount.setSession(this.session);
        linkedAccount.setDeviceID(this.deviceID);

        return LinkResult.AWAITING_FINALIZATION;
    }

    public FinalizeResult finalizeAddAuthenticator(String smsCode) {
        if (phoneNumber != null && !phoneNumber.isEmpty() && !checkSMSCode(smsCode)) {
            return FinalizeResult.BAD_SMS_CODE;
        }

        Map<String, String> postData = new HashMap<>();
        postData.put("steamid", String.valueOf(session.getSteamID()));
        postData.put("access_token", session.getOAuthToken());
        postData.put("activation_code", smsCode);
        int tries = 0;
        while (tries <= 30) {
            postData.put("authenticator_code", linkedAccount.generateSteamGuardCode());
            postData.put("authenticator_time", String.valueOf(TimeAligner.getSteamTime()));

            String response = SteamWeb.mobileLoginRequest(APIEndpoints.STEAMAPI_BASE + "/ITwoFactorService/FinalizeAddAuthenticator/v0001",
                    new HttpParameters(postData, HttpMethod.POST));

            if (response == null) return FinalizeResult.GENERAL_FAILURE;

            Type responseType = new TypeToken<SteamResponse<FinalizeAuthenticatorResponse>>(){}.getType();
            FinalizeAuthenticatorResponse finalizeResponse = new Gson().fromJson(response, responseType);

            if (finalizeResponse == null) return FinalizeResult.GENERAL_FAILURE;

            if (finalizeResponse.status == 89) {
                return FinalizeResult.BAD_SMS_CODE;
            }

            if (finalizeResponse.status == 88) {
                if (tries >= 30)
                    return FinalizeResult.UNABLE_TO_GENERATE_CORRECT_CODES;
            }

            if (!finalizeResponse.success) return FinalizeResult.GENERAL_FAILURE;

            if (finalizeResponse.wantMore) {
                tries++;
                continue;
            }

            this.linkedAccount.fullyEnrolled = true;

            return FinalizeResult.SUCCESS;
        }

        return FinalizeResult.GENERAL_FAILURE;
    }

    private boolean checkSMSCode(String smsCode) {
        Map<String, String> postData = new HashMap<>();
        postData.put("op", "check_sms_code");
        postData.put("arg", smsCode);
        postData.put("sessionid", session.getSessionID());

        String response = SteamWeb.fetch(APIEndpoints.COMMUNITY_BASE + "/steamguard/phoneajax",
                new HttpParameters(postData, HttpMethod.POST));

        if (response == null) return false;

        AddPhoneResponse addPhoneNumberResponse = new Gson().fromJson(response, AddPhoneResponse.class);

        JsonObject responseObject = new JsonParser().parse(response).getAsJsonObject();

        return addPhoneNumberResponse.success;
    }

    public static String generateDeviceID() {
        // Generate 8 random bytes
        final byte[] randomBytes = new byte[8];
        final SecureRandom random = new SecureRandom();
        random.nextBytes(randomBytes);

        // Generate sha1 hash
        try {
            MessageDigest  md = MessageDigest.getInstance("SHA-1");
            byte[] hashedBytes = md.digest(randomBytes);
            String random32 = IOHelper.decode(hashedBytes).replace("-", "").substring(0, 32).toLowerCase();

            return "android:" + splitOnRatios(random32, new int[] { 8, 4, 4, 4, 12}, "-");
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return "";
        }
    }

    private boolean addPhoneNumber() {
        Map<String, String> postData = new HashMap<>(3);
        postData.put("op", "add_phone_number");
        postData.put("arg", phoneNumber);
        postData.put("sessionid", session.getSessionID());

        String response = SteamWeb.fetch(APIEndpoints.COMMUNITY_BASE + "/steamguard/phoneajax",
                new HttpParameters(postData, HttpMethod.POST));

        if (response == null) return false;

        AddPhoneResponse addPhoneNumberResponse = new Gson().fromJson(response, AddPhoneResponse.class);

        return addPhoneNumberResponse.success;
    }

    private boolean hasPhoneAttached() {
        Map<String, String> postData = new HashMap<>(3);
        postData.put("op", "has_phone");
        postData.put("arg", "null");
        postData.put("sessionid", session.getSessionID());

        String response = SteamWeb.fetch(APIEndpoints.COMMUNITY_BASE + "/steamguard/phoneajax",
                new HttpParameters(postData, HttpMethod.POST));

        if (response == null) return false;

        HasPhoneResponse hasPhoneResponse = new Gson().fromJson(response, HasPhoneResponse.class);

        return hasPhoneResponse.hasPhone;
    }

    private static String splitOnRatios(String str, int[] ratios, String intermediate) {
        StringBuilder result = new StringBuilder();

        int pos = 0;
        for (int index = 0; index < ratios.length; index++) {
            result.append(str.substring(pos, ratios[index]));
            pos = ratios[index];

            if (index < ratios.length- 1)
                result.append(intermediate);
        }

        return result.toString();
    }

    private final class AddAuthenticatorResponse {
        @SerializedName("response")
        public SteamGuardAccount response;
    }

    private final class AddPhoneResponse {
        @SerializedName("success")
        public boolean success;
    }

    private final class FinalizeAuthenticatorResponse {
        @SerializedName("status")
        public int status;

        @SerializedName("server_time")
        public long serverTime;

        @SerializedName("want_more")
        public boolean wantMore;

        @SerializedName("success")
        public boolean success;
    }

    private final class HasPhoneResponse {
        @SerializedName("has_phone")
        public boolean hasPhone;
    }

    public enum LinkResult {
        MUST_PROVIDE_PHONE_NUMBER, //No phone number on the account
        MUST_REMOVE_PHONE_NUMBER, //A phone number is already on the account
        AWAITING_FINALIZATION, //Must provide an SMS code
        GENERAL_FAILURE, //General failure (really now!)
        AUTHENTICATOR_PRESENT
    }

    public enum FinalizeResult {
        BAD_SMS_CODE,
        UNABLE_TO_GENERATE_CORRECT_CODES,
        SUCCESS,
        GENERAL_FAILURE
    }
}