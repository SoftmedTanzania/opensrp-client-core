package org.smartregister.service;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.support.annotation.NonNull;
import android.util.Base64;
import android.util.Log;

import com.google.common.io.BaseEncoding;
import com.google.gson.Gson;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;
import org.apache.http.util.ByteArrayBuffer;
import org.smartregister.CoreLibrary;
import org.smartregister.DristhiConfiguration;
import org.smartregister.account.AccountAuthenticatorXml;
import org.smartregister.account.AccountError;
import org.smartregister.account.AccountHelper;
import org.smartregister.account.AccountResponse;
import org.smartregister.compression.GZIPCompression;
import org.smartregister.domain.DownloadStatus;
import org.smartregister.domain.LoginResponse;
import org.smartregister.domain.ProfileImage;
import org.smartregister.domain.Response;
import org.smartregister.domain.ResponseErrorStatus;
import org.smartregister.domain.ResponseStatus;
import org.smartregister.domain.jsonmapping.LoginResponseData;
import org.smartregister.repository.AllSettings;
import org.smartregister.repository.AllSharedPreferences;
import org.smartregister.ssl.OpensrpSSLHelper;
import org.smartregister.util.SyncUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;

import javax.net.ssl.HttpsURLConnection;

import timber.log.Timber;

import static org.smartregister.domain.LoginResponse.CUSTOM_SERVER_RESPONSE;
import static org.smartregister.domain.LoginResponse.MALFORMED_URL;
import static org.smartregister.domain.LoginResponse.NO_INTERNET_CONNECTIVITY;
import static org.smartregister.domain.LoginResponse.SUCCESS;
import static org.smartregister.domain.LoginResponse.SUCCESS_WITHOUT_TEAM_DETAILS;
import static org.smartregister.domain.LoginResponse.SUCCESS_WITHOUT_TEAM_LOCATION;
import static org.smartregister.domain.LoginResponse.SUCCESS_WITHOUT_TEAM_LOCATION_UUID;
import static org.smartregister.domain.LoginResponse.SUCCESS_WITHOUT_TEAM_NAME;
import static org.smartregister.domain.LoginResponse.SUCCESS_WITHOUT_TEAM_UUID;
import static org.smartregister.domain.LoginResponse.SUCCESS_WITHOUT_TIME;
import static org.smartregister.domain.LoginResponse.SUCCESS_WITHOUT_TIME_DETAILS;
import static org.smartregister.domain.LoginResponse.SUCCESS_WITHOUT_TIME_ZONE;
import static org.smartregister.domain.LoginResponse.SUCCESS_WITHOUT_USER_DETAILS;
import static org.smartregister.domain.LoginResponse.SUCCESS_WITHOUT_USER_LOCATION;
import static org.smartregister.domain.LoginResponse.SUCCESS_WITHOUT_USER_PREFERREDNAME;
import static org.smartregister.domain.LoginResponse.SUCCESS_WITHOUT_USER_USERNAME;
import static org.smartregister.domain.LoginResponse.SUCCESS_WITH_EMPTY_RESPONSE;
import static org.smartregister.domain.LoginResponse.TIMEOUT;
import static org.smartregister.domain.LoginResponse.UNAUTHORIZED;
import static org.smartregister.domain.LoginResponse.UNKNOWN_RESPONSE;
import static org.smartregister.util.HttpResponseUtil.getResponseBody;

public class HTTPAgent {
    private Context context;
    private AllSettings settings;
    private AllSharedPreferences allSharedPreferences;
    private DristhiConfiguration configuration;
    private GZIPCompression gzipCompression;

    private String boundary = "***" + System.currentTimeMillis() + "***";
    private String twoHyphens = "--";
    private String crlf = "\r\n";

    private int connectTimeout = 60000;
    private int readTimeout = 60000;

    private static final String DETAILS_URL = "/user-details?anm-id=";

    private SyncUtils syncUtils;

    public HTTPAgent(Context context, AllSettings settings, AllSharedPreferences
            allSharedPreferences, DristhiConfiguration configuration) {
        this.context = context;
        this.settings = settings;
        this.allSharedPreferences = allSharedPreferences;
        this.configuration = configuration;
        gzipCompression = new GZIPCompression();
        syncUtils = new SyncUtils(context.getApplicationContext());
    }

    private HttpURLConnection initializeHttp(String requestURLPath) throws IOException {
        URL url = new URL(requestURLPath);
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        if (urlConnection instanceof HttpsURLConnection) {
            OpensrpSSLHelper opensrpSSLHelper = new OpensrpSSLHelper(context, configuration);
            ((HttpsURLConnection) urlConnection).setSSLSocketFactory(opensrpSSLHelper.getSSLSocketFactory());
        }
        urlConnection.setConnectTimeout(getConnectTimeout());
        urlConnection.setReadTimeout(getReadTimeout());
        AccountAuthenticatorXml authenticatorXml = CoreLibrary.getInstance().getAccountAuthenticatorXml();
        if (AccountHelper.getOauthAccountByType(authenticatorXml.getAccountType()) != null)
            urlConnection.setRequestProperty("Authorization", new StringBuilder("Bearer ").append(AccountHelper.getOAuthToken(authenticatorXml.getAccountType(), AccountHelper.TOKEN_TYPE.PROVIDER)).toString());

        return urlConnection;
    }

    public Response<String> fetch(String requestURLPath) {
        try {

            HttpURLConnection urlConnection = httpURLConnectionTries(requestURLPath);

            return handleResponse(urlConnection);

        } catch (IOException ex) {
            Timber.e(ex, "EXCEPTION %s", ex.toString());
            return new Response<>(ResponseStatus.failure, null);
        }
    }

    @NonNull
    private HttpURLConnection httpURLConnectionTries(String requestURLPath) throws IOException {

        int authRetries = 0;
        HttpURLConnection urlConnection = null;

        while (authRetries < CoreLibrary.getInstance().getSyncConfiguration().getMaxAuthenticationRetries() + 1) {

            authRetries++;

            urlConnection = initializeHttp(requestURLPath);

            if (urlConnection.getResponseCode() == HttpStatus.SC_UNAUTHORIZED) {

                refreshAuthenticationToken(AccountHelper.getAccountManagerValue(AccountHelper.KEY_REFRESH_TOKEN, CoreLibrary.getInstance().getAccountAuthenticatorXml().getAccountType()));
            }

        }
        return urlConnection;
    }

    public Response<String> post(String postURLPath, String jsonPayload) {
        HttpURLConnection urlConnection;
        AccountAuthenticatorXml authenticatorXml = CoreLibrary.getInstance().getAccountAuthenticatorXml();
        try {
            urlConnection = initializeHttp(postURLPath);

            urlConnection.setDoOutput(true);
            urlConnection.setRequestMethod("POST");
            urlConnection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            urlConnection.setRequestProperty("Content-Encoding", "gzip");
            urlConnection.setRequestProperty("Authorization", new StringBuilder("Bearer ").append(AccountHelper.getOAuthToken(authenticatorXml.getAccountType(), AccountHelper.TOKEN_TYPE.PROVIDER)).toString());


            OutputStream os = urlConnection.getOutputStream();
            BufferedOutputStream writer = new BufferedOutputStream(os);
            writer.write(gzipCompression.compress(jsonPayload));
            writer.flush();
            writer.close();
            os.close();

            urlConnection.connect();

            return handleResponse(urlConnection);

        } catch (IOException ex) {
            Timber.e(ex, "EXCEPTION: %s", ex.toString());
            return new Response<>(ResponseStatus.failure, null);
        }
    }

    public Response<String> postWithJsonResponse(String postURLPath, String jsonPayload) {
        logResponse(postURLPath, jsonPayload);
        return post(postURLPath, jsonPayload);
    }

    private void logResponse(String postURLPath, String jsonPayload) {
        Timber.d("postURLPath: %s and jsonPayLoad: %s", postURLPath, jsonPayload);
    }

    public LoginResponse urlCanBeAccessWithGivenCredentials(String requestURL, String userName, String password) {
        LoginResponse loginResponse = null;
        HttpURLConnection urlConnection = null;
        String url = null;
        try {
            url = requestURL.replaceAll("\\s+", "");
            urlConnection = initializeHttp(url);

            final String basicAuth = "Basic " + Base64.encodeToString((userName + ":" + password).getBytes(), Base64.NO_WRAP);
            urlConnection.setRequestProperty("Authorization", basicAuth);
            int statusCode = urlConnection.getResponseCode();
            InputStream inputStream;
            if (statusCode >= HttpStatus.SC_BAD_REQUEST)
                inputStream = urlConnection.getErrorStream();
            else
                inputStream = urlConnection.getInputStream();
            String responseString = IOUtils.toString(inputStream);
            if (statusCode == HttpStatus.SC_OK) {

                Timber.d("response String: %s using request url %s", responseString, url);
                LoginResponseData responseData = getResponseBody(responseString);
                loginResponse = retrieveResponse(responseData);
            } else if (statusCode == HttpStatus.SC_UNAUTHORIZED) {
                Timber.e("Invalid credentials for: %s using %s", userName, url);
                loginResponse = UNAUTHORIZED;
            } else if (StringUtils.isNotBlank(responseString)) {
                //extract message string from the default tomcat server response which is usually between <p><b>message</b> and </u></p>
                responseString = StringUtils.substringBetween(responseString, "<p><b>message</b>", "</u></p>");
                if (StringUtils.isNotBlank(responseString)) {
                    //remove the underline tag from the responseString
                    responseString = responseString.replace("<u>", "").trim();
                    loginResponse = CUSTOM_SERVER_RESPONSE.withMessage(responseString);
                }
            } else {
                Timber.e("Bad response from Dristhi. Status code: %s username: %s using %s ", statusCode, userName, url);
                loginResponse = UNKNOWN_RESPONSE;
            }
        } catch (MalformedURLException e) {
            Timber.e(e, "Failed to check credentials bad url %s", url);
            loginResponse = MALFORMED_URL;
        } catch (SocketTimeoutException e) {
            Timber.e(e, "SocketTimeoutException when authenticating %s", userName);
            loginResponse = TIMEOUT;
            Timber.e(e, "Failed to check credentials of: %s using %s . Error: %s", userName, url, e.toString());
        } catch (IOException e) {
            Timber.e(e, "Failed to check credentials of: %s  using %s . Error: %s", userName, url, e.toString());
            loginResponse = NO_INTERNET_CONNECTIVITY;
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
        return loginResponse;
    }

    public DownloadStatus downloadFromUrl(String url, String filename) {

        AccountAuthenticatorXml authenticatorXml = CoreLibrary.getInstance().getAccountAuthenticatorXml();
        Response<DownloadStatus> status = downloadFromURL(url, filename, new StringBuilder("Bearer ").append(AccountHelper.getOAuthToken(authenticatorXml.getAccountType(), AccountHelper.TOKEN_TYPE.PROVIDER)).toString());
        Timber.d("downloading file name : %s and url %s", filename, url);
        return status.payload();
    }

    public Response<String> fetchWithCredentials(String requestURL) {

        try {

            HttpURLConnection urlConnection = httpURLConnectionTries(requestURL);
            return handleResponse(urlConnection);

        } catch (IOException ex) {
            Timber.e(ex, "EXCEPTION %s", ex.toString());
            return new Response<>(ResponseStatus.failure, null);
        }

    }

    private Response<String> handleResponse(HttpURLConnection urlConnection) {
        String responseString;
        try {
            int statusCode = urlConnection.getResponseCode();

            InputStream inputStream = null;

            if (statusCode == HttpStatus.SC_UNAUTHORIZED) {

                syncUtils.logoutUser();

            } else if (statusCode >= HttpStatus.SC_BAD_REQUEST)
                inputStream = urlConnection.getErrorStream();
            else
                inputStream = urlConnection.getInputStream();

            responseString = IOUtils.toString(inputStream);

            Timber.d("response string: %s using url %s", responseString, urlConnection.getURL());

        } catch (MalformedURLException e) {
            Timber.e(e, "%s %s", MALFORMED_URL, e.toString());
            ResponseStatus.failure.setDisplayValue(ResponseErrorStatus.malformed_url.name());
            return new Response<>(ResponseStatus.failure, null);
        } catch (SocketTimeoutException e) {
            Timber.e(e, "%s %s", TIMEOUT, e.toString());
            ResponseStatus.failure.setDisplayValue(ResponseErrorStatus.timeout.name());
            return new Response<>(ResponseStatus.failure, null);
        } catch (IOException e) {
            Timber.e(e, "%s %s", NO_INTERNET_CONNECTIVITY, e.toString());
            return new Response<>(ResponseStatus.failure, null);
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
        return new Response<>(ResponseStatus.success, responseString);
    }


    /**
     * @param urlString This is the url of the image, TAG,
     * @param image     This is the image to be uploaded to opensrp server.
     * @return String This returns the response obtained from the opensrp server.
     * @author Rodgers Andati
     * @since 2019-04-25
     * This method uploads an image to opensrp server. Migration from the old method that used httpclient
     */
    public String httpImagePost(String urlString, ProfileImage image) {
        OutputStream outputStream;
        PrintWriter writer;
        String responseString = "";
        AccountAuthenticatorXml authenticatorXml = CoreLibrary.getInstance().getAccountAuthenticatorXml();

        try {
            HttpURLConnection httpUrlConnection = initializeHttp(urlString);

            httpUrlConnection.setUseCaches(false);
            httpUrlConnection.setDoInput(true);
            httpUrlConnection.setDoOutput(true);
            httpUrlConnection.setRequestProperty("Content-Type", "multipart/form-data;boundary=" + boundary);
            httpUrlConnection.setRequestProperty("Authorization", new StringBuilder("Bearer ").append(AccountHelper.getOAuthToken(authenticatorXml.getAccountType(), AccountHelper.TOKEN_TYPE.PROVIDER)).toString());


            outputStream = httpUrlConnection.getOutputStream();
            writer = new PrintWriter(new OutputStreamWriter(outputStream, "UTF-8"), true);

            // attach image
            attachImage(writer, image, outputStream);

            // adding string params
            addParameter(writer, "anm-id", image.getAnmId());
            addParameter(writer, "entity-id", image.getEntityID());
            addParameter(writer, "content-type", image.getContenttype() != null ? image.getContenttype() : "jpeg");
            addParameter(writer, "file-category", image.getFilecategory() != null ? image.getFilecategory() : "profilepic");

            // send request to server
            writer.append(crlf).flush();
            writer.append(twoHyphens + boundary + twoHyphens).append(crlf);
            writer.close();

            // checks server's status code first
            int status = httpUrlConnection.getResponseCode();
            String line;
            if (status == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        httpUrlConnection.getInputStream()));

                while ((line = reader.readLine()) != null) {
                    responseString = line;
                    Timber.d("SERVER RESPONSE %s", line);
                }
                reader.close();
            } else {
                Timber.d("SERVER RESPONSE %s Server returned non-OK status: %s :-", status, httpUrlConnection.getResponseMessage());
                BufferedReader reader = new BufferedReader(new InputStreamReader(httpUrlConnection.getErrorStream()));
                while ((line = reader.readLine()) != null) {
                    Timber.d("SERVER RESPONSE %s", line);
                }
                reader.close();
            }
            httpUrlConnection.disconnect();

        } catch (ProtocolException e) {
            Timber.e(e, "Protocol exception %s", e.toString());
        } catch (SocketTimeoutException e) {
            Timber.e(e, "SocketTimeout %s %s", TIMEOUT, e.toString());
        } catch (MalformedURLException e) {
            Timber.e(e, "MalformedUrl %s %s", MALFORMED_URL, e.toString());
        } catch (IOException e) {
            Timber.e(e, "IOException %s %s", NO_INTERNET_CONNECTIVITY, e.toString());
        }
        return responseString;
    }

    private void attachImage(PrintWriter writer, ProfileImage image, OutputStream outputStream) throws IOException {
        File uploadImageFile = new File(image.getFilepath());
        String fileName = uploadImageFile.getName();

        writer.append("--" + boundary).append(crlf);
        writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"").append(crlf);
        writer.append("Content-Type: " + URLConnection.guessContentTypeFromName(fileName)).append(crlf);
        writer.append("Content-Transfer-Encoding: binary").append(crlf);
        writer.append(crlf);
        writer.flush();

        FileInputStream inputStream = new FileInputStream(uploadImageFile);
        byte[] buffer = new byte[4096];
        int bytesRead = -1;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
        }
        outputStream.flush();
        inputStream.close();

        writer.append(crlf);
        writer.flush();
    }

    private void addParameter(PrintWriter writer, String paramName, String paramValue) {
        writer.append(twoHyphens + boundary).append(crlf);
        writer.append("Content-Disposition: form-data; name=\"" + paramName + "\"").append(crlf);
        writer.append("Content-Type: text/plain; charset=" + "UTF-8").append(crlf);
        writer.append(crlf);
        writer.append(paramValue).append(crlf);
        writer.flush();

        Timber.d("http agent param name: %s and param value %s ", paramName, paramValue);
    }


    private LoginResponse retrieveResponse(LoginResponseData responseData) {
        if (responseData == null) {
            Timber.e("Empty Response using: %s ", SUCCESS_WITH_EMPTY_RESPONSE.name());
            return SUCCESS_WITH_EMPTY_RESPONSE;
        }

        if (responseData.team == null || responseData.team.team == null) {
            Timber.e("Empty Response in: %s ", SUCCESS_WITHOUT_TEAM_DETAILS.name());
            return SUCCESS_WITHOUT_TEAM_DETAILS.withPayload(responseData);
        } else if (responseData.team.team.location == null) {
            Timber.e("Empty Response in: %s", SUCCESS_WITHOUT_TEAM_LOCATION.name());
            return SUCCESS_WITHOUT_TEAM_LOCATION.withPayload(responseData);
        } else if (responseData.team.team.location.uuid == null) {
            Timber.e("Empty Response in: %s", SUCCESS_WITHOUT_TEAM_LOCATION_UUID.name());
            return SUCCESS_WITHOUT_TEAM_LOCATION_UUID.withPayload(responseData);
        } else if (responseData.team.team.uuid == null) {
            Timber.e("Empty Response in: %s", SUCCESS_WITHOUT_TEAM_UUID.name());
            return SUCCESS_WITHOUT_TEAM_UUID.withPayload(responseData);
        } else if (responseData.team.team.teamName == null) {
            Timber.e("Empty Response in: %s", SUCCESS_WITHOUT_TEAM_NAME.name());
            return SUCCESS_WITHOUT_TEAM_NAME.withPayload(responseData);
        }

        if (responseData.user == null) {
            Timber.e("Empty Response in: %s", SUCCESS_WITHOUT_USER_DETAILS.name());
            return SUCCESS_WITHOUT_USER_DETAILS.withPayload(responseData);
        } else if (responseData.user.getUsername() == null) {
            Timber.e("Empty Response in: %s", SUCCESS_WITHOUT_USER_USERNAME.name());
            return SUCCESS_WITHOUT_USER_USERNAME.withPayload(responseData);
        } else if (responseData.user.getPreferredName() == null) {
            Timber.e("Empty Response in: %s", SUCCESS_WITHOUT_USER_PREFERREDNAME.name());
            return SUCCESS_WITHOUT_USER_PREFERREDNAME.withPayload(responseData);
        }

        if (responseData.locations == null) {
            Timber.e("Empty Response in: %s", SUCCESS_WITHOUT_USER_LOCATION.name());
            return SUCCESS_WITHOUT_USER_LOCATION.withPayload(responseData);
        }
        if (responseData.time == null) {
            Timber.e("Empty Response in: %s", SUCCESS_WITHOUT_TIME_DETAILS.name());
            return SUCCESS_WITHOUT_TIME_DETAILS.withPayload(responseData);
        } else if (responseData.time.getTime() == null) {
            Timber.e("Empty Response in: %s", SUCCESS_WITHOUT_TIME.name());
            return SUCCESS_WITHOUT_TIME.withPayload(responseData);
        } else if (responseData.time.getTimeZone() == null) {
            Timber.e("Empty Response in: %s", SUCCESS_WITHOUT_TIME_ZONE.name());
            return SUCCESS_WITHOUT_TIME_ZONE.withPayload(responseData);
        }

        return SUCCESS.withPayload(responseData);
    }

    /**
     * Returns the read timeout in milliseconds
     *
     * @return read timeout value in milliseconds
     */
    public int getReadTimeout() {
        return readTimeout;
    }

    /**
     * Returns the connection timeout in milliseconds
     *
     * @return connection timeout value in milliseconds
     */
    public int getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * Sets the connection timeout in milliseconds
     * <p>
     * Setting this will call {@link java.net.HttpURLConnection#setConnectTimeout(int)}
     * on the {@link java.net.HttpURLConnection} instance in {@link org.smartregister.service.HTTPAgent}
     */
    public void setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    /**
     * Sets the read timeout in milliseconds
     * <p>
     * Setting this will call {@link java.net.HttpURLConnection#setReadTimeout(int)}
     * on the {@link java.net.HttpURLConnection} instance in {@link org.smartregister.service.HTTPAgent}
     */
    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }

    public AccountResponse oauth2authenticate(String username, String password, String grantType) {

        AccountError accountError = null;
        HttpURLConnection urlConnection = null;
        String url = null;
        String baseURL = configuration.dristhiBaseURL() + AccountHelper.OAUTH.TOKEN_ENDPOINT;
        String requestURL = baseURL + "?&grant_type=" + grantType + "&username=" + username + "&password=" + password;
        try {
            url = requestURL.replaceAll("\\s+", "");
            urlConnection = initializeHttp(url);

            String clientId = CoreLibrary.getInstance().getSyncConfiguration().getOauthClientId();
            String clientSecret = CoreLibrary.getInstance().getSyncConfiguration().getOauthClientSecret();

            final String base64Auth = BaseEncoding.base64().encode(new String(clientId + ":" + clientSecret).getBytes());

            urlConnection.setRequestMethod("POST");
            urlConnection.addRequestProperty("client_id", clientId);
            urlConnection.addRequestProperty("client_secret", clientSecret);
            urlConnection.setRequestProperty("Authorization", "Basic " + base64Auth);


            int statusCode = urlConnection.getResponseCode();
            InputStream inputStream;
            if (statusCode >= HttpStatus.SC_BAD_REQUEST)
                inputStream = urlConnection.getErrorStream();
            else
                inputStream = urlConnection.getInputStream();
            String responseString = IOUtils.toString(inputStream);
            if (statusCode == HttpStatus.SC_OK) {

                Timber.d("response String: %s using request url %s", responseString, url);

                AccountResponse accountResponse = new Gson().fromJson(responseString, AccountResponse.class);
                return accountResponse;

            } else {

                accountError = new Gson().fromJson(responseString, AccountError.class);
                return new AccountResponse(statusCode, accountError);

            }
        } catch (MalformedURLException e) {
            Timber.e(e, "Failed to check credentials bad url %s", url);
            accountError = new AccountError(0, MALFORMED_URL.name());

        } catch (SocketTimeoutException e) {
            Timber.e(e, "SocketTimeoutException when authenticating %s", username);

            accountError = new AccountError(0, TIMEOUT.name());

            Timber.e(e, "Failed to check credentials of: %s using %s . Error: %s", username, url, e.toString());
        } catch (IOException e) {
            Timber.e(e, "Failed to check credentials of: %s  using %s . Error: %s", username, url, e.toString());
            accountError = new AccountError(0, NO_INTERNET_CONNECTIVITY.name());

        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }

        }

        //If we got here there was an issue with no server status code
        return new AccountResponse(0, accountError);

    }

    public LoginResponse fetchUserDetails(String requestURL, String oauthAccessToken) {
        LoginResponse loginResponse = null;
        String url = null;
        HttpURLConnection urlConnection = null;
        try {
            url = requestURL.replaceAll("\\s+", "");

            urlConnection = httpURLConnectionTries(url);

            int statusCode = urlConnection.getResponseCode();

            InputStream inputStream;
            if (statusCode >= HttpStatus.SC_BAD_REQUEST)
                inputStream = urlConnection.getErrorStream();
            else
                inputStream = urlConnection.getInputStream();
            String responseString = IOUtils.toString(inputStream);
            if (statusCode == HttpStatus.SC_OK) {

                Timber.d("response String: %s using request url %s", responseString, url);
                LoginResponseData responseData = getResponseBody(responseString);
                loginResponse = retrieveResponse(responseData);
            } else if (statusCode == HttpStatus.SC_UNAUTHORIZED) {
                Timber.e("Invalid credentials for: %s using token %s", oauthAccessToken, url);
                loginResponse = UNAUTHORIZED;
            } else if (StringUtils.isNotBlank(responseString)) {
                //extract message string from the default tomcat server response which is usually between <p><b>message</b> and </u></p>
                responseString = StringUtils.substringBetween(responseString, "<p><b>message</b>", "</u></p>");
                if (StringUtils.isNotBlank(responseString)) {
                    //remove the underline tag from the responseString
                    responseString = responseString.replace("<u>", "").trim();
                    loginResponse = CUSTOM_SERVER_RESPONSE.withMessage(responseString);
                }
            } else {
                Timber.e("Bad response from Server. Status code: %s using %s ", statusCode, url);
                loginResponse = UNKNOWN_RESPONSE;
            }
        } catch (MalformedURLException e) {
            Timber.e(e, "Failed to check credentials bad url %s", url);
            loginResponse = MALFORMED_URL;
        } catch (SocketTimeoutException e) {
            Timber.e(e, "SocketTimeoutException when authenticating");
            loginResponse = TIMEOUT;
        } catch (IOException e) {
            Timber.e(e, "Failed to connect to %s check, check internet connection. Error: %s", url, e.toString());
            loginResponse = NO_INTERNET_CONNECTIVITY;
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
        return loginResponse;
    }

    /**
     * @param downloadURL_ This is the url of the image
     * @param fileName     This is how the image should be name after it has been downloaded.
     * @param accessToken  This is the access token used to authenticate when accessing the url endpoint.
     * @return Response<DownloadStatus> This returns whether the download succeeded or failed.
     */
    public Response<DownloadStatus> downloadFromURL(String downloadURL_, String fileName, String accessToken) {
        HttpURLConnection httpUrlConnection;
        try {
            File dir = new File(FormPathService.sdcardPathDownload);

            if (!dir.exists()) {
                dir.mkdirs();
            }

            File file = new File(dir, fileName);

            long startTime = System.currentTimeMillis();
            Log.d("DownloadFormService", "download begin");
            Log.d("DownloadFormService", "download url: " + downloadURL_);
            Log.d("DownloadFormService", "download file name: " + fileName);


            String downloadURL = downloadURL_.replaceAll("\\s+", "");

            /* Open connection to URL */
            URL url = new URL(downloadURL);

            httpUrlConnection = (HttpURLConnection) url.openConnection();

            if (httpUrlConnection instanceof HttpsURLConnection) {
                OpensrpSSLHelper opensrpSSLHelper = new OpensrpSSLHelper(context, configuration);
                ((HttpsURLConnection) httpUrlConnection).setSSLSocketFactory(opensrpSSLHelper.getSSLSocketFactory());
            }

            httpUrlConnection.setRequestProperty("Authorization", accessToken);

            httpUrlConnection.connect();

            int status = httpUrlConnection.getResponseCode();
            if (status == HttpURLConnection.HTTP_OK) {

                InputStream inputStream = httpUrlConnection.getInputStream();
                BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);

                long fileLength = bufferedInputStream.available();
                if (fileLength == 0) {
                    return new Response<DownloadStatus>(ResponseStatus.success,
                            DownloadStatus.nothingDownloaded);
                }
                Log.d("DownloadFormService", "file length : " + fileLength);

                ByteArrayBuffer baf = new ByteArrayBuffer(9999);
                int current = 0;
                while ((current = bufferedInputStream.read()) != -1) {
                    baf.append((byte) current);
                }

                /* Convert the bytes to String */
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(baf.toByteArray());
                fos.flush();
                fos.close();

                Log.d("DownloadFormService",
                        "download finished in " + ((System.currentTimeMillis() - startTime) / 1000)
                                + " sec");
                httpUrlConnection.disconnect();

            } else {
                Log.d("RESPONSE", "Server returned non-OK status: " + status);
                return new Response<DownloadStatus>(ResponseStatus.failure, DownloadStatus.failedDownloaded);
            }

        } catch (IOException e) {
            Log.d("DownloadFormService", "download error : " + e);
            return new Response<DownloadStatus>(ResponseStatus.success, DownloadStatus.failedDownloaded);
        }

        return new Response<DownloadStatus>(ResponseStatus.success, DownloadStatus.downloaded);
    }

    public boolean verifyAuthorization() {
        String baseUrl = configuration.dristhiBaseURL();

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        final String username = allSharedPreferences.fetchRegisteredANM();
        baseUrl = baseUrl + DETAILS_URL + username;
        try {
            URL url = new URL(baseUrl);
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();

            AccountAuthenticatorXml authenticatorXml = CoreLibrary.getInstance().getAccountAuthenticatorXml();
            if (AccountHelper.getOauthAccountByType(authenticatorXml.getAccountType()) != null)
                urlConnection.setRequestProperty("Authorization", new StringBuilder("Bearer ").append(AccountHelper.getOAuthToken(authenticatorXml.getAccountType(), AccountHelper.TOKEN_TYPE.PROVIDER)).toString());
            int statusCode = urlConnection.getResponseCode();
            urlConnection.disconnect();
            if (statusCode == HttpStatus.SC_UNAUTHORIZED) {
                Timber.i("User not authorized. User access was revoked, will log off user");
                return false;
            } else if (statusCode != HttpStatus.SC_OK) {
                Timber.w("Error occurred verifying authorization, User will not be logged off");
            } else {
                Timber.i("User is Authorized");
            }

        } catch (IOException e) {
            Timber.e(e);
        }
        return true;
    }

    public AccountResponse oauth2authenticateRefreshToken(String refreshToken) {

        AccountError accountError = null;
        HttpURLConnection urlConnection = null;
        String url = null;

        String clientId = CoreLibrary.getInstance().getSyncConfiguration().getOauthClientId();
        String clientSecret = CoreLibrary.getInstance().getSyncConfiguration().getOauthClientSecret();

        String baseURL = configuration.dristhiBaseURL() + AccountHelper.OAUTH.TOKEN_ENDPOINT;
        String requestURL = baseURL + "?&grant_type=" + AccountHelper.OAUTH.GRANT_TYPE.REFRESH_TOKEN + "&refresh_token=" + refreshToken + "&client_id=" + clientId;
        try {
            url = requestURL.replaceAll("\\s+", "");
            urlConnection = initializeHttp(url);


            final String base64Auth = BaseEncoding.base64().encode(new String(clientId + ":" + clientSecret).getBytes());

            urlConnection.setRequestMethod("POST");
            urlConnection.addRequestProperty("client_id", clientId);
            urlConnection.addRequestProperty("client_secret", clientSecret);
            urlConnection.setRequestProperty("Authorization", "Bearer " + base64Auth);


            int statusCode = urlConnection.getResponseCode();
            InputStream inputStream;
            if (statusCode >= HttpStatus.SC_BAD_REQUEST)
                inputStream = urlConnection.getErrorStream();
            else
                inputStream = urlConnection.getInputStream();
            String responseString = IOUtils.toString(inputStream);
            if (statusCode == HttpStatus.SC_OK) {

                Timber.d("response String: %s using request url %s", responseString, url);

                AccountResponse accountResponse = new Gson().fromJson(responseString, AccountResponse.class);
                return accountResponse;

            } else {

                accountError = new Gson().fromJson(responseString, AccountError.class);
                return new AccountResponse(statusCode, accountError);

            }
        } catch (MalformedURLException e) {
            Timber.e(e, "Failed to check credentials bad url %s", url);
            accountError = new AccountError(0, MALFORMED_URL.name());

        } catch (SocketTimeoutException e) {
            Timber.e(e, "SocketTimeoutException when authenticating %s", refreshToken);

            accountError = new AccountError(0, TIMEOUT.name());

            Timber.e(e, "Failed to check credentials of: %s using %s . Error: %s", refreshToken, url, e.toString());
        } catch (IOException e) {
            Timber.e(e, "Failed to check credentials of: %s  using %s . Error: %s", refreshToken, url, e.toString());
            accountError = new AccountError(0, NO_INTERNET_CONNECTIVITY.name());

        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }

        }

        //If we got here there was an issue with no server status code
        return new AccountResponse(0, accountError);

    }

    private String refreshAuthenticationToken(String refreshToken) {

        AccountResponse response = CoreLibrary.getInstance().context().getHttpAgent().oauth2authenticateRefreshToken(refreshToken);

        AccountAuthenticatorXml authenticatorXml = CoreLibrary.getInstance().getAccountAuthenticatorXml();
        AccountManager accountManager = CoreLibrary.getInstance().getAccountManager();


        Account account = AccountHelper.getOauthAccountByType(authenticatorXml.getAccountType());
        String authToken = accountManager.peekAuthToken(account, AccountHelper.TOKEN_TYPE.PROVIDER);

        accountManager.setAuthToken(account, AccountHelper.TOKEN_TYPE.PROVIDER, response.getAccessToken());
        accountManager.setPassword(account, response.getAccessToken());
        accountManager.setUserData(account, AccountHelper.KEY_REFRESH_TOKEN, response.getRefreshToken());

        return response.getAccessToken();
    }
}