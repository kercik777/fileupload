package com.bayram.githubfileupload;

import android.os.Handler;
import android.os.Looper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Управление авторизацией через GitHub OAuth (Web Flow без кода с авто-возвратом gitstorage://callback
 * + резервный Device Flow), репозиториями и ветками:
 * - мгновенный вход в браузере с автоматическим возвратом в приложение после нажатия Authorize;
 * - автоматическое определение пользователя (@login, аватар) по любому токену;
 * - загрузка полного списка репозиториев аккаунта;
 * - создание нового публичного репозитория с README.md в один клик;
 * - полное удаление репозитория вместе со всеми файлами и релизами;
 * - загрузка всех веток репозитория, создание новых веток и удаление дополнительных веток.
 */
public class GitHubAccountManager {

    public static final String CLIENT_ID = "Ov23licUnHLVbIdjeM2D";

    /**
     * ВСТАВЬТЕ СЮДА СВОЙ CLIENT SECRET В SKETCHWARE PRO ПЕРЕД СБОРКОЙ APK
     * (из настроек вашего OAuth App на GitHub -> Client secrets):
     */
    public static final String CLIENT_SECRET = "";

    public static final String REDIRECT_URI = "gitstorage://callback";
    public static final String OAUTH_SCOPES = "repo delete_repo";

    private static final MediaType JSON_MT = MediaType.get("application/json; charset=utf-8");

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    public static class DeviceCodeInfo {
        public String deviceCode = "";
        public String userCode = "";
        public String verificationUri = "https://github.com/login/device";
        public int expiresIn = 900;
        public int interval = 5;
    }

    public static class GitHubUser {
        public String login = "";
        public String name = "";
        public String avatarUrl = "";
    }

    public static class AuthResult {
        public String accessToken = "";
        public GitHubUser user = new GitHubUser();
    }

    public static class UserRepo {
        public String owner = "";
        public String name = "";
        public String fullName = "";
        public String defaultBranch = "main";
        public boolean privateRepo;
        public String description = "";
    }

    public static class TokenReposResult {
        public GitHubUser user = new GitHubUser();
        public List<UserRepo> repos = new ArrayList<>();
    }

    public static class BranchInfo {
        public String name = "";
        public String sha = "";
        public boolean isDefault;
        public boolean protectedBranch;
    }

    public static class BranchListResult {
        public String defaultBranch = "main";
        public List<BranchInfo> branches = new ArrayList<>();
    }

    /** Управляемая сессия опроса OAuth-токена для Device Flow. */
    public final class PollSession {
        private final String deviceCode;
        private volatile int intervalSec;
        private final Callback<AuthResult> callback;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean inFlight = new AtomicBoolean(false);
        private final Runnable scheduledTick = this::executeSinglePoll;

        PollSession(String deviceCode, int initialIntervalSec, Callback<AuthResult> callback) {
            this.deviceCode = deviceCode;
            this.intervalSec = Math.max(5, initialIntervalSec);
            this.callback = callback;
        }

        void start() {
            scheduleNext(intervalSec);
        }

        private void scheduleNext(int delaySec) {
            if (cancelled.get()) {
                return;
            }
            ui.removeCallbacks(scheduledTick);
            ui.postDelayed(scheduledTick, Math.max(1, delaySec) * 1000L);
        }

        /** Вызывается при возврате пользователя из браузера, чтобы проверить вход мгновенно. */
        public void pollNow() {
            if (cancelled.get()) {
                return;
            }
            ui.removeCallbacks(scheduledTick);
            executeSinglePoll();
        }

        public void cancel() {
            cancelled.set(true);
            ui.removeCallbacks(scheduledTick);
        }

        private void executeSinglePoll() {
            if (cancelled.get() || !inFlight.compareAndSet(false, true)) {
                return;
            }
            new Thread(() -> {
                try {
                    FormBody form = new FormBody.Builder()
                            .add("client_id", CLIENT_ID)
                            .add("device_code", deviceCode)
                            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                            .build();

                    Request req = new Request.Builder()
                            .url("https://github.com/login/oauth/access_token")
                            .header("Accept", "application/json")
                            .header("User-Agent", "GitHubUploader/2.4")
                            .post(form)
                            .build();

                    try (Response resp = http.newCall(req).execute()) {
                        String body = readBody(resp);
                        JsonElement el = JsonParser.parseString(body);
                        if (!el.isJsonObject()) {
                            scheduleNext(intervalSec);
                            return;
                        }
                        JsonObject obj = el.getAsJsonObject();
                        String accessToken = str(obj, "access_token");
                        if (!accessToken.isEmpty()) {
                            GitHubUser user = fetchUserSync(accessToken);
                            if (cancelled.compareAndSet(false, true)) {
                                AuthResult res = new AuthResult();
                                res.accessToken = accessToken;
                                res.user = user;
                                ui.post(() -> callback.onSuccess(res));
                            }
                            return;
                        }

                        String err = str(obj, "error");
                        if ("authorization_pending".equalsIgnoreCase(err)) {
                            scheduleNext(intervalSec);
                        } else if ("slow_down".equalsIgnoreCase(err)) {
                            if (obj.has("interval") && !obj.get("interval").isJsonNull()) {
                                intervalSec = Math.max(intervalSec + 5, obj.get("interval").getAsInt());
                            } else {
                                intervalSec += 5;
                            }
                            scheduleNext(intervalSec);
                        } else if ("expired_token".equalsIgnoreCase(err)) {
                            failAndStop("Время действия кода истекло. Запросите новый код.");
                        } else if ("access_denied".equalsIgnoreCase(err)) {
                            failAndStop("Авторизация отклонена в браузере.");
                        } else if ("device_flow_disabled".equalsIgnoreCase(err)) {
                            failAndStop("В настройках OAuth App на GitHub включите галочку «Enable Device Flow» и нажмите Update application.");
                        } else {
                            String desc = str(obj, "error_description");
                            failAndStop(!desc.isEmpty() ? desc : ("Ошибка авторизации: " + err));
                        }
                    }
                } catch (Exception e) {
                    scheduleNext(intervalSec);
                } finally {
                    inFlight.set(false);
                }
            }).start();
        }

        private void failAndStop(final String message) {
            if (cancelled.compareAndSet(false, true)) {
                ui.removeCallbacks(scheduledTick);
                ui.post(() -> callback.onError(message));
            }
        }
    }

    private final OkHttpClient http;
    private final Handler ui;

    public GitHubAccountManager() {
        http = new OkHttpClient.Builder()
                .connectTimeout(25, TimeUnit.SECONDS)
                .readTimeout(35, TimeUnit.SECONDS)
                .build();
        ui = new Handler(Looper.getMainLooper());
    }

    /** Проверяет, вставлен ли CLIENT_SECRET для бескодового Web Flow с авто-возвратом. */
    public static boolean hasClientSecret() {
        return CLIENT_SECRET != null && !CLIENT_SECRET.trim().isEmpty();
    }

    /** Генерирует случайный защитный параметр state для OAuth-сессии. */
    public static String generateRandomState() {
        byte[] bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        }
        return sb.toString();
    }

    /** Формирует прямую ссылку согласия GitHub OAuth (без ввода одноразового кода). */
    public static String buildAuthorizeUrl(String state) {
        HttpUrl.Builder builder = new HttpUrl.Builder()
                .scheme("https")
                .host("github.com")
                .addPathSegments("login/oauth/authorize")
                .addQueryParameter("client_id", CLIENT_ID.trim())
                .addQueryParameter("redirect_uri", REDIRECT_URI)
                .addQueryParameter("scope", OAUTH_SCOPES)
                .addQueryParameter("allow_signup", "true");
        if (state != null && !state.isEmpty()) {
            builder.addQueryParameter("state", state);
        }
        return builder.build().toString();
    }

    /**
     * Обменивает параметр ?code=... из gitstorage://callback на рабочий токен GitHub
     * и сразу загружает профиль пользователя (@login, имя, аватар).
     */
    public void exchangeOAuthCode(final String rawCode, final String rawState,
                                  Callback<AuthResult> cb) {
        final String code = rawCode == null ? "" : rawCode.trim();
        final String secret = CLIENT_SECRET == null ? "" : CLIENT_SECRET.trim();
        if (code.isEmpty()) {
            post(cb, null, "GitHub не передал код авторизации");
            return;
        }
        if (secret.isEmpty()) {
            post(cb, null, "Вставьте ваш Client Secret в переменную CLIENT_SECRET в начале файла GitHubAccountManager.java");
            return;
        }

        new Thread(() -> {
            try {
                FormBody.Builder formBuilder = new FormBody.Builder()
                        .add("client_id", CLIENT_ID.trim())
                        .add("client_secret", secret)
                        .add("code", code)
                        .add("redirect_uri", REDIRECT_URI);
                if (rawState != null && !rawState.trim().isEmpty()) {
                    formBuilder.add("state", rawState.trim());
                }

                Request req = new Request.Builder()
                        .url("https://github.com/login/oauth/access_token")
                        .header("Accept", "application/json")
                        .header("User-Agent", "GitHubUploader/2.5")
                        .post(formBuilder.build())
                        .build();

                try (Response resp = http.newCall(req).execute()) {
                    String body = readBody(resp);
                    JsonElement parsed = body.isEmpty() ? null : JsonParser.parseString(body);
                    JsonObject obj = (parsed != null && parsed.isJsonObject())
                            ? parsed.getAsJsonObject() : null;

                    if (obj == null) {
                        post(cb, null, "Некорректный ответ GitHub OAuth (HTTP " + resp.code() + ")");
                        return;
                    }

                    String accessToken = str(obj, "access_token");
                    if (!accessToken.isEmpty()) {
                        GitHubUser user = fetchUserSync(accessToken);
                        AuthResult res = new AuthResult();
                        res.accessToken = accessToken;
                        res.user = user;
                        post(cb, res, null);
                        return;
                    }

                    String err = str(obj, "error");
                    if ("incorrect_client_credentials".equalsIgnoreCase(err)) {
                        post(cb, null, "Неверный CLIENT_SECRET в GitHubAccountManager.java — проверьте скопированный ключ.");
                    } else if ("redirect_uri_mismatch".equalsIgnoreCase(err)) {
                        post(cb, null, "В настройках OAuth App на GitHub укажите Authorization callback URL: gitstorage://callback");
                    } else if ("bad_verification_code".equalsIgnoreCase(err)) {
                        post(cb, null, "Сессия входа устарела — нажмите «Авторизоваться через GitHub» ещё раз.");
                    } else {
                        String desc = str(obj, "error_description");
                        post(cb, null, !desc.isEmpty() ? desc : ("Ошибка авторизации GitHub: " + err));
                    }
                }
            } catch (ApiError ae) {
                post(cb, null, ae.getMessage());
            } catch (Exception e) {
                String msg = e.getMessage();
                post(cb, null, (msg == null || msg.isEmpty()) ? "Ошибка сети при завершении входа в GitHub" : msg);
            }
        }).start();
    }

    /** Шаг 1 Device Flow: запрашиваем user_code и verification_uri у GitHub. */
    public void requestDeviceCode(Callback<DeviceCodeInfo> cb) {
        new Thread(() -> {
            try {
                FormBody form = new FormBody.Builder()
                        .add("client_id", CLIENT_ID)
                        .add("scope", OAUTH_SCOPES)
                        .build();

                Request req = new Request.Builder()
                        .url("https://github.com/login/device/code")
                        .header("Accept", "application/json")
                        .header("User-Agent", "GitHubUploader/2.4")
                        .post(form)
                        .build();

                try (Response resp = http.newCall(req).execute()) {
                    String body = readBody(resp);
                    JsonElement parsed = body.isEmpty() ? null : JsonParser.parseString(body);
                    JsonObject obj = (parsed != null && parsed.isJsonObject())
                            ? parsed.getAsJsonObject() : null;

                    if (obj != null && obj.has("error")) {
                        String err = str(obj, "error");
                        if ("device_flow_disabled".equalsIgnoreCase(err)) {
                            post(cb, null, "В настройках вашего OAuth App на GitHub нужно включить галочку «Enable Device Flow» и нажать Update application.");
                            return;
                        }
                        String desc = str(obj, "error_description");
                        post(cb, null, !desc.isEmpty() ? desc : ("Ошибка GitHub OAuth: " + err));
                        return;
                    }

                    if (!resp.isSuccessful() || obj == null) {
                        post(cb, null, "Не удалось получить код входа (HTTP " + resp.code() + ")");
                        return;
                    }

                    DeviceCodeInfo info = new DeviceCodeInfo();
                    info.deviceCode = str(obj, "device_code");
                    info.userCode = str(obj, "user_code");
                    String uri = str(obj, "verification_uri");
                    if (!uri.isEmpty()) {
                        info.verificationUri = uri;
                    }
                    if (obj.has("expires_in") && !obj.get("expires_in").isJsonNull()) {
                        info.expiresIn = obj.get("expires_in").getAsInt();
                    }
                    if (obj.has("interval") && !obj.get("interval").isJsonNull()) {
                        info.interval = Math.max(5, obj.get("interval").getAsInt());
                    }
                    if (info.deviceCode.isEmpty() || info.userCode.isEmpty()) {
                        post(cb, null, "GitHub вернул пустой код авторизации");
                        return;
                    }
                    post(cb, info, null);
                }
            } catch (Exception e) {
                String msg = e.getMessage();
                post(cb, null, (msg == null || msg.isEmpty()) ? "Ошибка сети при подключении к GitHub" : msg);
            }
        }).start();
    }

    /** Шаг 2 Device Flow: запускаем фоновый опрос получения токена. */
    public PollSession startPollingToken(DeviceCodeInfo info, Callback<AuthResult> cb) {
        PollSession session = new PollSession(info.deviceCode, info.interval, cb);
        session.start();
        return session;
    }

    /**
     * Загружает профиль владельца токена (@login, аватар) и полный список всех его репозиториев.
     */
    public void loadUserAndRepos(final String rawToken, Callback<TokenReposResult> cb) {
        final String token = rawToken == null ? "" : rawToken.trim();
        if (token.isEmpty()) {
            post(cb, null, "Токен не указан");
            return;
        }
        new Thread(() -> {
            try {
                GitHubUser user = fetchUserSync(token);
                List<UserRepo> repos = new ArrayList<>();

                for (int page = 1; page <= 2; page++) {
                    HttpUrl url = new HttpUrl.Builder()
                            .scheme("https")
                            .host("api.github.com")
                            .addPathSegments("user/repos")
                            .addQueryParameter("affiliation", "owner")
                            .addQueryParameter("sort", "updated")
                            .addQueryParameter("direction", "desc")
                            .addQueryParameter("per_page", "100")
                            .addQueryParameter("page", String.valueOf(page))
                            .build();

                    Request req = apiAuth(token).url(url).get().build();
                    try (Response resp = http.newCall(req).execute()) {
                        if (resp.code() == 401) {
                            throw new ApiError("Неверный или просроченный токен GitHub (HTTP 401)");
                        }
                        if (!resp.isSuccessful()) {
                            throw new ApiError("Не удалось загрузить репозитории: HTTP " + resp.code());
                        }
                        JsonElement el = JsonParser.parseString(readBody(resp));
                        if (!el.isJsonArray()) {
                            break;
                        }
                        JsonArray arr = el.getAsJsonArray();
                        for (JsonElement item : arr) {
                            if (!item.isJsonObject()) continue;
                            repos.add(parseRepo(item.getAsJsonObject(), user.login));
                        }
                        if (arr.size() < 100) {
                            break;
                        }
                    }
                }

                TokenReposResult out = new TokenReposResult();
                out.user = user;
                out.repos = repos;
                post(cb, out, null);
            } catch (ApiError ae) {
                post(cb, null, ae.getMessage());
            } catch (Exception e) {
                String msg = e.getMessage();
                post(cb, null, (msg == null || msg.isEmpty()) ? "Ошибка сети при загрузке репозиториев" : msg);
            }
        }).start();
    }

    /**
     * Создаёт новый публичный репозиторий с README.md (auto_init: true).
     */
    public void createPublicRepo(final String rawToken, final String rawRepoName, Callback<UserRepo> cb) {
        final String token = rawToken == null ? "" : rawToken.trim();
        final String cleanName = sanitizeRepoName(rawRepoName);
        if (token.isEmpty()) {
            post(cb, null, "Сначала авторизуйтесь или введите токен");
            return;
        }
        if (cleanName.isEmpty()) {
            post(cb, null, "Введите корректное имя репозитория (латиницей или цифрами)");
            return;
        }

        new Thread(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("name", cleanName);
                body.addProperty("description", "File storage created via GitHub Uploader");
                body.addProperty("private", false);
                body.addProperty("auto_init", true);

                Request req = apiAuth(token)
                        .url("https://api.github.com/user/repos")
                        .post(RequestBody.create(JSON_MT, body.toString()))
                        .build();

                try (Response resp = http.newCall(req).execute()) {
                    String respBody = readBody(resp);
                    if (resp.isSuccessful()) {
                        JsonObject obj = JsonParser.parseString(respBody).getAsJsonObject();
                        UserRepo created = parseRepo(obj, "");
                        post(cb, created, null);
                        return;
                    }
                    if (resp.code() == 422) {
                        if (respBody.toLowerCase().contains("already exists")) {
                            post(cb, null, "Репозиторий «" + cleanName + "» уже существует в вашем аккаунте");
                        } else {
                            post(cb, null, "Недопустимое имя репозитория «" + cleanName + "» (HTTP 422)");
                        }
                        return;
                    }
                    if (resp.code() == 401 || resp.code() == 403) {
                        post(cb, null, "У токена нет прав на создание репозитория (нужно право repo / public_repo)");
                        return;
                    }
                    post(cb, null, "Ошибка создания репозитория: HTTP " + resp.code());
                }
            } catch (Exception e) {
                String msg = e.getMessage();
                post(cb, null, (msg == null || msg.isEmpty()) ? "Ошибка сети при создании репозитория" : msg);
            }
        }).start();
    }

    /**
     * Полностью удаляет репозиторий вместе со всеми загруженными файлами, ветками и релизами.
     */
    public void deleteRepo(final String rawToken, final String owner, final String repo,
                           Callback<Boolean> cb) {
        final String token = rawToken == null ? "" : rawToken.trim();
        if (token.isEmpty() || owner == null || owner.isEmpty() || repo == null || repo.isEmpty()) {
            post(cb, null, "Не указаны данные репозитория для удаления");
            return;
        }
        new Thread(() -> {
            try {
                HttpUrl url = new HttpUrl.Builder()
                        .scheme("https")
                        .host("api.github.com")
                        .addPathSegments("repos/" + owner.trim() + "/" + repo.trim())
                        .build();

                Request req = apiAuth(token).url(url).delete().build();
                try (Response resp = http.newCall(req).execute()) {
                    if (resp.code() == 204 || resp.code() == 404 || resp.isSuccessful()) {
                        post(cb, true, null);
                        return;
                    }
                    if (resp.code() == 403) {
                        post(cb, null, "У этого токена нет права delete_repo. Войдите через вкладку «Через GitHub» или включите галочку delete_repo у токена.");
                        return;
                    }
                    if (resp.code() == 401) {
                        post(cb, null, "Неверный или просроченный токен (HTTP 401)");
                        return;
                    }
                    post(cb, null, "Ошибка удаления репозитория: HTTP " + resp.code());
                }
            } catch (Exception e) {
                String msg = e.getMessage();
                post(cb, null, (msg == null || msg.isEmpty()) ? "Ошибка сети при удалении репозитория" : msg);
            }
        }).start();
    }

    /** Загружает все ветки указанного репозитория (основная ветка ставится первой). */
    public void listRepoBranches(final String rawToken, final String owner, final String repo,
                                 Callback<BranchListResult> cb) {
        final String token = rawToken == null ? "" : rawToken.trim();
        if (token.isEmpty() || owner == null || owner.isEmpty() || repo == null || repo.isEmpty()) {
            post(cb, null, "Сначала выберите репозиторий");
            return;
        }
        new Thread(() -> {
            try {
                String defaultBranch = "main";
                HttpUrl repoUrl = new HttpUrl.Builder()
                        .scheme("https")
                        .host("api.github.com")
                        .addPathSegments("repos/" + owner.trim() + "/" + repo.trim())
                        .build();
                try (Response metaResp = http.newCall(apiAuth(token).url(repoUrl).get().build()).execute()) {
                    if (metaResp.code() == 404) {
                        post(cb, null, "Репозиторий " + owner + "/" + repo + " не найден");
                        return;
                    }
                    if (metaResp.isSuccessful()) {
                        JsonObject meta = JsonParser.parseString(readBody(metaResp)).getAsJsonObject();
                        String db = str(meta, "default_branch");
                        if (!db.isEmpty()) {
                            defaultBranch = db;
                        }
                    }
                }

                HttpUrl brUrl = new HttpUrl.Builder()
                        .scheme("https")
                        .host("api.github.com")
                        .addPathSegments("repos/" + owner.trim() + "/" + repo.trim() + "/branches")
                        .addQueryParameter("per_page", "100")
                        .build();

                List<BranchInfo> list = new ArrayList<>();
                try (Response resp = http.newCall(apiAuth(token).url(brUrl).get().build()).execute()) {
                    if (!resp.isSuccessful()) {
                        post(cb, null, "Ошибка загрузки веток: HTTP " + resp.code());
                        return;
                    }
                    JsonElement parsed = JsonParser.parseString(readBody(resp));
                    if (parsed.isJsonArray()) {
                        for (JsonElement el : parsed.getAsJsonArray()) {
                            if (!el.isJsonObject()) continue;
                            JsonObject b = el.getAsJsonObject();
                            BranchInfo info = new BranchInfo();
                            info.name = str(b, "name");
                            if (b.has("commit") && b.get("commit").isJsonObject()) {
                                info.sha = str(b.getAsJsonObject("commit"), "sha");
                            }
                            info.protectedBranch = b.has("protected")
                                    && !b.get("protected").isJsonNull()
                                    && b.get("protected").getAsBoolean();
                            info.isDefault = info.name.equalsIgnoreCase(defaultBranch);
                            if (!info.name.isEmpty()) {
                                list.add(info);
                            }
                        }
                    }
                }

                Collections.sort(list, (a, b) -> {
                    if (a.isDefault != b.isDefault) {
                        return a.isDefault ? -1 : 1;
                    }
                    return a.name.compareToIgnoreCase(b.name);
                });

                BranchListResult res = new BranchListResult();
                res.defaultBranch = defaultBranch;
                res.branches = list;
                post(cb, res, null);
            } catch (Exception e) {
                String msg = e.getMessage();
                post(cb, null, (msg == null || msg.isEmpty()) ? "Ошибка сети при загрузке веток" : msg);
            }
        }).start();
    }

    /** Создаёт новую ветку в репозитории на основе его основной ветки. */
    public void createBranch(final String rawToken, final String owner, final String repo,
                             final String rawNewBranch, final String baseBranchHint,
                             Callback<BranchInfo> cb) {
        final String token = rawToken == null ? "" : rawToken.trim();
        final String cleanBranch = sanitizeBranchName(rawNewBranch);
        if (token.isEmpty()) {
            post(cb, null, "Токен не указан");
            return;
        }
        if (cleanBranch.isEmpty()) {
            post(cb, null, "Введите корректное имя ветки");
            return;
        }

        new Thread(() -> {
            try {
                String baseBranch = (baseBranchHint == null || baseBranchHint.trim().isEmpty())
                        ? "main" : baseBranchHint.trim();

                // Уточняем актуальную default_branch и SHA базового коммита
                HttpUrl repoUrl = new HttpUrl.Builder()
                        .scheme("https")
                        .host("api.github.com")
                        .addPathSegments("repos/" + owner.trim() + "/" + repo.trim())
                        .build();
                try (Response metaResp = http.newCall(apiAuth(token).url(repoUrl).get().build()).execute()) {
                    if (metaResp.isSuccessful()) {
                        JsonObject meta = JsonParser.parseString(readBody(metaResp)).getAsJsonObject();
                        String db = str(meta, "default_branch");
                        if (!db.isEmpty()) {
                            baseBranch = db;
                        }
                    }
                }

                HttpUrl refUrl = new HttpUrl.Builder()
                        .scheme("https")
                        .host("api.github.com")
                        .addPathSegments("repos/" + owner.trim() + "/" + repo.trim()
                                + "/git/refs/heads/" + baseBranch)
                        .build();

                String baseSha = "";
                try (Response refResp = http.newCall(apiAuth(token).url(refUrl).get().build()).execute()) {
                    if (!refResp.isSuccessful()) {
                        post(cb, null, "Не удалось прочитать основную ветку «" + baseBranch
                                + "» (возможно, репозиторий совсем пустой — загрузите хотя бы README)");
                        return;
                    }
                    JsonObject refObj = JsonParser.parseString(readBody(refResp)).getAsJsonObject();
                    if (refObj.has("object") && refObj.get("object").isJsonObject()) {
                        baseSha = str(refObj.getAsJsonObject("object"), "sha");
                    }
                }

                if (baseSha.isEmpty()) {
                    post(cb, null, "Не удалось получить SHA основной ветки");
                    return;
                }

                HttpUrl createUrl = new HttpUrl.Builder()
                        .scheme("https")
                        .host("api.github.com")
                        .addPathSegments("repos/" + owner.trim() + "/" + repo.trim() + "/git/refs")
                        .build();

                JsonObject body = new JsonObject();
                body.addProperty("ref", "refs/heads/" + cleanBranch);
                body.addProperty("sha", baseSha);

                try (Response createResp = http.newCall(
                        apiAuth(token).url(createUrl)
                                .post(RequestBody.create(JSON_MT, body.toString()))
                                .build()).execute()) {
                    String respBody = readBody(createResp);
                    if (createResp.isSuccessful()
                            || (createResp.code() == 422 && respBody.toLowerCase().contains("already exists"))) {
                        BranchInfo created = new BranchInfo();
                        created.name = cleanBranch;
                        created.sha = baseSha;
                        created.isDefault = cleanBranch.equalsIgnoreCase(baseBranch);
                        post(cb, created, null);
                        return;
                    }
                    post(cb, null, "Ошибка создания ветки «" + cleanBranch + "»: HTTP " + createResp.code());
                }
            } catch (Exception e) {
                String msg = e.getMessage();
                post(cb, null, (msg == null || msg.isEmpty()) ? "Ошибка сети при создании ветки" : msg);
            }
        }).start();
    }

    /** Удаляет дополнительную ветку из репозитория. */
    public void deleteBranch(final String rawToken, final String owner, final String repo,
                             final String branchName, Callback<Boolean> cb) {
        final String token = rawToken == null ? "" : rawToken.trim();
        if (token.isEmpty() || branchName == null || branchName.trim().isEmpty()) {
            post(cb, null, "Имя ветки не указано");
            return;
        }
        new Thread(() -> {
            try {
                HttpUrl url = new HttpUrl.Builder()
                        .scheme("https")
                        .host("api.github.com")
                        .addPathSegments("repos/" + owner.trim() + "/" + repo.trim()
                                + "/git/refs/heads/" + branchName.trim())
                        .build();
                try (Response resp = http.newCall(apiAuth(token).url(url).delete().build()).execute()) {
                    if (resp.code() == 204 || resp.code() == 404 || resp.isSuccessful()) {
                        post(cb, true, null);
                    } else if (resp.code() == 422) {
                        post(cb, null, "Основную или защищённую ветку удалить нельзя");
                    } else {
                        post(cb, null, "Ошибка удаления ветки: HTTP " + resp.code());
                    }
                }
            } catch (Exception e) {
                String msg = e.getMessage();
                post(cb, null, (msg == null || msg.isEmpty()) ? "Ошибка сети при удалении ветки" : msg);
            }
        }).start();
    }

    public static String sanitizeRepoName(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().replace(' ', '-');
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.') {
                out.append(c);
            }
        }
        String res = out.toString();
        while (res.startsWith(".") || res.startsWith("-")) {
            res = res.substring(1);
        }
        if (res.endsWith(".git")) {
            res = res.substring(0, res.length() - 4);
        }
        return res;
    }

    public static String sanitizeBranchName(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().replace(' ', '-');
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '/') {
                out.append(c);
            }
        }
        String res = out.toString();
        while (res.startsWith("/") || res.startsWith(".") || res.startsWith("-")) {
            res = res.substring(1);
        }
        while (res.endsWith("/") || res.endsWith(".")) {
            res = res.substring(0, res.length() - 1);
        }
        return res;
    }

    private GitHubUser fetchUserSync(String token) throws Exception {
        Request req = apiAuth(token)
                .url("https://api.github.com/user")
                .get()
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (resp.code() == 401) {
                throw new ApiError("Неверный токен или доступ отозван (HTTP 401)");
            }
            if (!resp.isSuccessful()) {
                throw new ApiError("Ошибка получения профиля GitHub: HTTP " + resp.code());
            }
            JsonObject obj = JsonParser.parseString(readBody(resp)).getAsJsonObject();
            GitHubUser u = new GitHubUser();
            u.login = str(obj, "login");
            u.name = str(obj, "name");
            u.avatarUrl = str(obj, "avatar_url");
            return u;
        }
    }

    private static UserRepo parseRepo(JsonObject obj, String fallbackOwner) {
        UserRepo r = new UserRepo();
        r.name = str(obj, "name");
        r.fullName = str(obj, "full_name");
        if (obj.has("owner") && obj.get("owner").isJsonObject()) {
            r.owner = str(obj.getAsJsonObject("owner"), "login");
        }
        if (r.owner.isEmpty()) {
            r.owner = fallbackOwner == null ? "" : fallbackOwner;
        }
        String defBranch = str(obj, "default_branch");
        r.defaultBranch = defBranch.isEmpty() ? "main" : defBranch;
        r.privateRepo = obj.has("private") && !obj.get("private").isJsonNull()
                && obj.get("private").getAsBoolean();
        r.description = str(obj, "description");
        return r;
    }

    private Request.Builder apiAuth(String token) {
        return new Request.Builder()
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "GitHubUploader/2.4");
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return "";
        }
        return o.get(key).getAsString();
    }

    private static String readBody(Response response) {
        try {
            ResponseBody b = response.body();
            return b == null ? "" : b.string();
        } catch (Exception e) {
            return "";
        }
    }

    private <T> void post(Callback<T> cb, T result, String error) {
        ui.post(() -> {
            if (error != null) {
                cb.onError(error);
            } else {
                cb.onSuccess(result);
            }
        });
    }

    private static final class ApiError extends Exception {
        ApiError(String msg) {
            super(msg);
        }
    }
}
