package com.manateams.scraper;

import android.support.annotation.Nullable;

import com.manateams.scraper.data.ClassGrades;
import com.manateams.scraper.data.Course;
import com.manateams.scraper.districts.TEAMSUserType;
import com.manateams.scraper.districts.impl.AustinISDParent;
import com.manateams.scraper.districts.impl.AustinISDStudent;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.HashMap;

import javax.net.ssl.SSLSocketFactory;

public class TEAMSGradeRetriever {


    private final String REGEX_USER_TYPE = "^[sS]\\d{6,8}\\d?$";
    private final TEAMSGradeParser parser;
    private String chooseUser;

    public TEAMSGradeRetriever() {
        parser = new TEAMSGradeParser();
    }

    public TEAMSUserType getUserType(final String username) {
        if(username.matches(REGEX_USER_TYPE)) {
            return new AustinISDStudent();
        } else {
            return new AustinISDParent();
        }
    }

    @Nullable
    public String getNewCookie(final String username, final String password, final TEAMSUserType userType) {
        try {
//            final String cStoneCookie = getAISDCookie(username, password);
            final String TEAMSCookie = getTEAMSCookie(username, password, userType);
            return TEAMSCookie;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Nullable
    public String[][] getStudentIDsAndNames(final String username, final String password, final String teamsUser, final String teamsPassword, final String cookie, final TEAMSUserType userType) {
        try {
            if (teamsUser != null && teamsUser.length() > 0) {
                return postTEAMSLogin(teamsUser, teamsPassword, cookie, userType);
            } else {
                return postTEAMSLogin(username, password, cookie, userType);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public String getNewUserIdentification(final String studentID, final String cookie, final TEAMSUserType userType) {
        try {
            final int idIndex = parser.parseStudentInfoIndex(studentID, chooseUser);
            String studentInfoLocID = "";
            if (idIndex != -1) {
                studentInfoLocID = parser.parseStudentInfoLocID(idIndex, chooseUser);
            } else {
                return null;
            }

            doRawPOSTRequest(userType.teamsHost(), "/selfserve/ViewStudentListChangeTabDisplayAction.do", new String[]{
                    "Cookie: " + cookie,
                    "Accept: */*",
                    "User-Agent: QHAC"
            }, "selectedIndexId=" + idIndex + "&studentLocId=" + studentInfoLocID + "&selectedTable=table");
            return "&selectedIndexId=" + idIndex + "&studentLocId=" + studentInfoLocID + "&selectedTable=table";
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public ClassGrades getCycleClassGrades(Course course, int cycle, String cookie, final TEAMSUserType userType, String userIdentification) throws  IOException{
        final TEAMSGradeParser parser = new TEAMSGradeParser();
        final String averageHtml = getTEAMSPage("/selfserve/PSSViewReportCardsAction.do", "", cookie, userType, userIdentification);

        final Element coursehtmlnode = parser.getCourseElement(averageHtml,course,cycle);
        final String gradeBookKey = "selectedIndexId=-1&smartFormName=SmartForm&gradeBookKey=" + URLEncoder.encode(coursehtmlnode.getElementsByTag("a").get(0).id(), "UTF-8");
        final String coursehtml = getTEAMSPage("/selfserve/PSSViewGradeBookEntriesAction.do", gradeBookKey, cookie, userType, userIdentification);
        //TODO hardcoded number of cycles
        return parser.parseClassGrades(coursehtml, course.courseId, cycle < 3 ? 0 : 1, cycle);
    }

    public String getTEAMSPage(final String path, final String gradeBookKey, final String cookie, final TEAMSUserType userType, final String userIdentification) throws IOException {
        final HashMap<String, String> data = new HashMap<>();
        data.put("Cookie", cookie);
        return doPOSTRequest("https://" + userType.teamsHost() + path, data, gradeBookKey + userIdentification);
    }

    /*
    Returns a new set of user information if user is a parent account.
     */
    public String[][] postTEAMSLogin(final String username, final String password, final String cookie, final TEAMSUserType userType) throws IOException {
        final String query = "userLoginId=" + URLEncoder.encode(username, "UTF-8") + "&userPassword=" + URLEncoder.encode(password, "UTF-8");

        final String[] headers = new String[]{
                "Cookie: " + cookie,
                "Accept: */*",
                "User-Agent: QHAC"
        };

//        String postResult = doRawPOSTRequest(userType.teamsHost(), "/selfserve/SignOnLoginAction.do", headers, query);

        final HashMap<String, String> data = new HashMap<>();
        data.put("Cookie", cookie);
        data.put("Accept", "*/*");
        data.put("User-Agent", "QHAC");
        String postResult = doPOSTRequest("https://grades.austinisd.org/selfserve/SignOnLoginAction.do", data, query);

        if (userType.isParent()) {
            try {
                chooseUser = getTEAMSPage("/selfserve/ViewStudentListAction.do", "", cookie, userType, "");

                final Document doc = Jsoup.parse(chooseUser);
                String[] studentIDs = doc.getElementById("tableBodyTable").children().select("tr > td:nth-child(1)").text().split(" ");
                String[] studentNames = doc.getElementById("tableBodyTable").children().select("tr > td:nth-child(2)").text().split("(?<!,) ");
                return new String[][]{studentIDs, studentNames};
            } catch (IOException e) {
                return null;
            }
        } else {
            return null;
        }
    }

    private String getAISDCookie(final String username, final String password) throws IOException {
        final String rawQuery = "[Client.Hardware]=&[User.ViewportSize]=1920x954&cn=" + username + "&[password]=" + password;

        final String[] headers = new String[]{
                "Origin: https://my.austinisd.org",
                "Upgrade-Insecure-Requests: 1",
                "User-Agent: Mozilla/5.0 (X11; Linux x86_64; rv:55.0) Gecko/20100101 Firefox/55.0",
                "Accept: */*",
                "Referer: https://my.austinisd.org/LoginPolicy.jsp",
                "Accept-Encoding: gzip, deflate, br",
                "Accept-Language: en-US,en;q=0.8"
        };

        final String response = doRawPOSTRequest("my.austinisd.org", "/WebNetworkAuth/", headers, rawQuery);

        for (final String line : response.split("\n")) {
            if (line.startsWith("Set-Cookie: CStoneSessionID=")) {
                return line.substring(12).split(";")[0];
            }
        }

        return null;
    }

    private String getTEAMSCookie(final String username, final String password, final TEAMSUserType userType) throws IOException {

        final String[] headers = new String[]{
                "Upgrade-Insecure-Requests: 1",
                "User-Agent: Mozilla/5.0 (X11; Linux x86_64; rv:55.0) Gecko/20100101 Firefox/55.0",
                "Accept: */*",
                "Accept-Encoding: gzip, deflate, br",
                "Accept-Language: en-US,en;q=0.8"
        };

        String parentStr = userType.isParent() ? "true" : "false";
        final String response = doGETRequest("https://grades.austinisd.org/selfserve/EntryPointSignOnAction.do?parent=" + parentStr);

        for (final String line : response.split("\n")) {
            if (line.startsWith("Set-Cookie: JSESSIONID=")) {
                return line.substring(12).split(";")[0];
            }
        }

        return null;
    }

    private String doPOSTRequest(final String url, final HashMap<String, String> headers, final String data) {
        final OkHttpClient client = new OkHttpClient();
        final MediaType type = MediaType.parse("application/x-www-form-urlencoded; charset=utf-8");

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "OkHttp Headers.java")
                .addHeader("Cookie", headers.get("Cookie"))
                .post(RequestBody.create(type, data))
                .build();

        String responseString = null;
        try {
            Response response = client.newCall(request).execute();
            if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
            responseString = response.body().string();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return responseString;
    }

    private String doGETRequest(final String url) {
        final OkHttpClient client = new OkHttpClient();

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "OkHttp Headers.java")
                .get()
                .build();

        String responseString = null;
        try {
            Response response = client.newCall(request).execute();
            if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
            responseString = response.headers().toString();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return responseString;
    }

    private String doRawPOSTRequest(final String host, final String path, final String[] headers, final String postData) throws IOException {
        final Socket socket = SSLSocketFactory.getDefault().createSocket(host,
                443);
        try {
            final PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                    socket.getOutputStream()));
            writer.println("POST " + path + " HTTP/1.1");
            writer.println("Host: " + host);
            for (String header : headers) {
                writer.println(header);
            }
            writer.println("Content-Length: " + postData.length());
            writer.println("Content-Type: application/x-www-form-urlencoded");
            writer.println();
            writer.println(postData);
            writer.println();
            writer.flush();

            StringBuilder response = new StringBuilder();

            final BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream()));
            final char[] buffer = new char[1024];
            int len;
            while ((len = reader.read(buffer)) > 0) {
                response.append(buffer, 0, len);
                if (response.length() >= 4 && response.substring(response.length() - 4).equals("\r\n\r\n")) {
                    break;
                }
            }
            return response.toString();
        } finally {
            socket.close();
        }
    }
}