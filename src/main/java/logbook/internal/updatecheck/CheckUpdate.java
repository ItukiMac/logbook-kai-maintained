package logbook.internal.updatecheck;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import logbook.internal.Launcher;
import logbook.internal.ThreadManager;
import logbook.internal.gui.InternalFXMLLoader;
import logbook.internal.gui.Tools;
import logbook.internal.gui.WindowHolder;
import logbook.internal.logger.LoggerHolder;

/**
 * アップデートチェック
 *
 */
public class CheckUpdate {

    /** GitHub リポジトリのパス */
    public static final String REPOSITORY_PATH = System.getProperty(
            "logbook.update.repository", "ItukiMac/logbook-kai-maintained");

    /** 更新確認先 Github tags API */
    private static final String TAGS = "https://api.github.com/repos/" + REPOSITORY_PATH + "/tags";

    /** 更新確認先 Github releases API */
    private static final String RELEASES = "https://api.github.com/repos/" + REPOSITORY_PATH + "/releases/tags/";

    /** ダウンロードサイトを開くを選択したときに開くURL */
    private static final String OPEN_URL = "https://github.com/" + REPOSITORY_PATH + "/releases";

    /** 検索するtagの名前 */
    /* 例えばv20.1.1 の 20.1.1にマッチ */
    static final Pattern TAG_REGIX = Pattern.compile("^v(\\d+\\.\\d+(?:\\.\\d+)?)$");

    /** Prerelease を使う System Property */
    private static final String USE_PRERELEASE = "logbook.use.prerelease";

    public static void run(Stage stage) {
        run(false, stage);
    }

    public static void run(boolean isStartUp) {
        run(isStartUp, null);
    }

    private static void run(boolean isStartUp, Stage stage) {
        Version remoteVersion = remoteVersion();

        if (!Version.UNKNOWN.equals(remoteVersion) && Version.getCurrent().compareTo(remoteVersion) < 0) {
            Platform.runLater(() -> CheckUpdate.openInfo(Version.getCurrent(), remoteVersion, isStartUp, stage));
        } else if (!isStartUp) {
            Tools.Controls.alert(AlertType.INFORMATION, "更新の確認", "最新のバージョンです。", stage);
        }
    }

    /**
     * 最新のバージョンを取得します。
     * @return 最新のバージョン
     */
    private static Version remoteVersion() {
        try {
            JsonArray tags;
            try (JsonReader r = Json.createReader(new ByteArrayInputStream(readURI(URI.create(TAGS))))) {
                tags = r.readArray();
            }
            // Githubのtagsから一番新しいreleasesを取ってくる
            // tagsを処理する
            return tags.stream()
                    //　tagの名前
                    .map(val -> val.asJsonObject().getString("name"))
                    // tagの名前にバージョンを含む?実行中のバージョンより新しい?
                    .filter(tagname -> {
                        Matcher m = TAG_REGIX.matcher(tagname);
                        if (m.matches()) {
                            Version remote = new Version(m.group(1));
                            return (!Version.UNKNOWN.equals(remote) && Version.getCurrent().compareTo(remote) < 0);
                        }
                        return false;
                    })
                    // tagがreleasesにある?
                    .filter(name -> {
                        try {
                            JsonObject releases;
                            try (JsonReader r = Json
                                    .createReader(new ByteArrayInputStream(readURI(URI.create(RELEASES + name))))) {
                                releases = r.readObject();
                            }
                            // releasesにない場合は "message": "Not Found"
                            if (releases.getString("message", null) != null)
                                return false;
                            // draftではない
                            if (releases.getBoolean("draft", false))
                                return false;
                            // prereleaseではない
                            if (!Boolean.getBoolean(USE_PRERELEASE) && releases.getBoolean("prerelease", false))
                                return false;
                            // assetsが1つ以上ある
                            if (releases.getJsonArray("assets") == null || releases.getJsonArray("assets").size() == 0)
                                return false;
                            // 最新版が見つかった!
                            return true;
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .findFirst()
                    .map(tagname -> {
                        Matcher m = TAG_REGIX.matcher(tagname);
                        if (!m.matches()) return Version.UNKNOWN;
                        return new Version(m.group(1));
                    })
                    .orElse(Version.UNKNOWN);
        } catch (Exception e) {
            LoggerHolder.get().warn("最新バージョンの取得に失敗しました", e);
        }
        return Version.UNKNOWN;
    }

    private static byte[] readURI(URI uri) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        try {
            // タイムアウトを設定
            connection.setConnectTimeout((int) TimeUnit.SECONDS.toMillis(10));
            connection.setReadTimeout((int) TimeUnit.SECONDS.toMillis(5));
            // 200 OKの場合にURIを読み取る
            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = connection.getInputStream()) {
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = in.read(buffer, 0, buffer.length)) > 0) {
                        out.write(buffer, 0, len);
                    }
                }
                return out.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
        return new byte[0];
    }

    private static void openInfo(Version o, Version n, boolean isStartUp, Stage stage) {
        String message = "新しいバージョンがあります。ダウンロードサイトを開きますか？\n"
                + "現在のバージョン:" + o + "\n"
                + "新しいバージョン:" + n;
        if (isStartUp) {
            message += "\n※自動アップデートチェックは[その他]-[設定]から無効に出来ます";
        }

        ButtonType update = new ButtonType("自動更新");
        ButtonType visible = new ButtonType("ダウンロードサイトを開く");
        ButtonType no = new ButtonType("後で");

        Alert alert = new Alert(AlertType.INFORMATION);
        alert.getDialogPane().getStylesheets().add("logbook/gui/application.css");
        InternalFXMLLoader.setGlobal(alert.getDialogPane());
        alert.setTitle("新しいバージョン");
        alert.setHeaderText("新しいバージョン");
        alert.setContentText(message);
        alert.initOwner(stage);
        alert.getButtonTypes().clear();
        alert.getButtonTypes().addAll(update, visible, no);
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent()) {
            if (result.get() == update)
                try {
                    launchUpdate(n.toString());
                } catch (Exception e) {
                    LoggerHolder.get().error("Update処理内でエラー発生", e);
                }
            if (result.get() == visible)
                openBrowser();
        }
    }

    private static void openBrowser() {
        try {
            ThreadManager.getExecutorService()
                    .submit(() -> {
                        Desktop.getDesktop()
                                .browse(URI.create(OPEN_URL));
                        return null;
                    });
        } catch (Exception e) {
            LoggerHolder.get().warn("アップデートチェックで例外", e);
        }
    }

    private static void launchUpdate(String version) {

        Stage mainStage = WindowHolder.getInstance().getMainWindow();
        if (mainStage == null) {
            LoggerHolder.get().error("メインウィンドウが取得できませんでした。");
            return;
        }

        // ルートレイアウト
        StackPane pane = new StackPane();
        pane.setPrefSize(700, 600);
        pane.setPadding(new Insets(6));
        pane.getStyleClass().add("root-pane"); // スタイルクラス

        // メインコンテナ
        VBox vbox = new VBox();
        vbox.getStyleClass().add("main-container");

        // ラベル部分
        TextFlow label = new TextFlow(
                new Text("航海日誌 v" + version + "への更新を行います。\n"),
                new Text("更新の準備が出来ましたら[更新]を押して更新を行ってください。"));
        label.getStyleClass().add("info-label");
        
        // "Github Releaseページを開く" リンク
        Hyperlink githubLink = new Hyperlink("Github Releaseページを開く");
        githubLink.setOnAction(event -> {
            try {
                Desktop.getDesktop().browse(new URI(OPEN_URL));
            } catch (Exception e) {
                LoggerHolder.get().error("Github Releaseページ表示時にエラー発生", e);
            }
        });
        
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS); 
        
        HBox labelBox = new HBox(5, label, spacer, githubLink);
        labelBox.setAlignment(Pos.CENTER_LEFT);
        vbox.getChildren().add(labelBox);
        
        // WebViewコンテナ
        StackPane stackPane1 = new StackPane();
        stackPane1.getStyleClass().add("webview-container");
        VBox.setVgrow(stackPane1, Priority.ALWAYS);

        WebView webView = new WebView();
        WebEngine webEngine = webView.getEngine();
        webEngine.loadContent(
                "<html>" +
                        "<head>" +
                        "<script src=\"https://cdnjs.cloudflare.com/ajax/libs/marked/0.3.6/marked.min.js\"></script>" +
                        "<link href=\"https://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/css/bootstrap.min.css\" rel=\"stylesheet\">"
                        +
                        "<style>" +
                        "body { font-family: 'Meiryo UI', Meiryo, 'Segoe UI', 'Lucida Grande', Verdana, Arial, Helvetica, sans-serif; }"
                        +
                        "</style>" +
                        "</head>" +
                        "<body class=\"container\"></body>" +
                        "</html>");
        stackPane1.getChildren().add(webView);

        // ボタンとチェックボックス
        StackPane stackPane2 = new StackPane();
        stackPane2.getStyleClass().add("button-container");
        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER);
        buttonBox.getStyleClass().add("button-box");

        Button updateButton = new Button("更新");
        updateButton.getStyleClass().add("update-button");
        updateButton.setOnAction(event -> startUpdateProcess(version, webEngine));

        // 閉じるボタン
        Button closeButton = new Button("閉じる");
        closeButton.getStyleClass().add("close-button");
        closeButton.setOnAction(event -> {
            Stage stage = (Stage) closeButton.getScene().getWindow();
            stage.close(); // 現在のウィンドウを閉じる
        });
        
        buttonBox.getChildren().addAll(updateButton, closeButton);
        stackPane2.getChildren().add(buttonBox);

        vbox.getChildren().addAll(stackPane1, stackPane2);
        pane.getChildren().add(vbox);

        // リリースノート取得
        try {
            String apiUrl = RELEASES + "v" + version;
            HttpURLConnection connection = (HttpURLConnection) new URL(apiUrl).openConnection();
            connection.setConnectTimeout(10 * 1000); // 接続タイムアウトを10秒に設定
            connection.setReadTimeout(10 * 1000); // 読み取りタイムアウトを10秒に設定
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
            connection.setRequestProperty("User-Agent", "JavaFX-App");

            if (connection.getResponseCode() != 200) {
                throw new Exception("GitHub API エラー: HTTP " + connection.getResponseCode());
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line).append("\n");
                }
                
                String releaseJson = response.toString();
                try {
                    String releaseNotes = extractReleaseNotes(releaseJson);
                    
                    // JavaScript内でエスケープするために、リリースノートの内容を処理
                    String escapedReleaseNotes = releaseNotes
                            .replace("'", "\\'")   // シングルクォートのエスケープ
                            .replace("\\", "\\\\") // バックスラッシュのエスケープ                        
                            .replace("\"", "\\\"") // ダブルクォートのエスケープ
                            .replace("\r", "")     // キャリッジリターンを削除
                            .replace("\n", "\\n"); // 改行をJavaScript用のエスケープに変換
    
                    // WebViewにリリースノートを挿入
                    webEngine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
                        if (newState == Worker.State.SUCCEEDED) {
                            webEngine.executeScript(
                                    "(function() {" +
                                            "if (window.marked && document.body) {" +
                                              "document.body.innerHTML = window.marked('" + escapedReleaseNotes + "');" +
                                            "}" +
                                        "})();");
                        }
                    });

                } catch (Exception e) {
                    LoggerHolder.get().error("releaseJson:" + releaseJson, e);
                    
                    webEngine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
                        if (newState == Worker.State.SUCCEEDED) {
                            webEngine.executeScript(
                                    "(function() {" +
                                            "if (window.marked && document.body) {" +
                                              "document.body.innerHTML = '<p>リリース情報の解析中にエラーが発生しました。Githubのリリースノートから確認してください。</p>';" +
                                            "}" +
                                          "})();");
                        }
                    });
                }
            }
        } catch (Exception e) {
            LoggerHolder.get().error("APIからのReleaseNoteJson情報の取得に失敗", e);

            webEngine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
                if (newState == Worker.State.SUCCEEDED) {
                    webEngine.executeScript(
                            "(function() {" +
                                  "if (window.marked && document.body) {" +
                                    "document.body.innerHTML = '<p>リリース情報の取得に失敗しました。Githubのリリースノートから確認してください。</p>';" +
                                  "}" +
                                "})();");
                }
            });
        }
                
        // スタイルシートを適用
        Scene scene = new Scene(pane);

        // UpdateWindowを開く
        Stage updateStage = new Stage();
        updateStage.setTitle("航海日誌の更新");
        // 親ウィンドウの中央に表示
        updateStage.setX(mainStage.getX() + (mainStage.getWidth() - 600) / 2);
        updateStage.setY(mainStage.getY() + (mainStage.getHeight() - 400) / 2);

        updateStage.setScene(scene);
        updateStage.initOwner(mainStage);
        updateStage.show();
    }

    /**
     * GitHub リリース JSON からリリースノートを抽出
     */
    private static String extractReleaseNotes(String releaseJson) {

        try (StringReader stringReader = new StringReader(releaseJson);
                JsonReader jsonReader = Json.createReader(stringReader)) {

            JsonObject jsonObject = jsonReader.readObject();

            return jsonObject.getString("body");

        } catch (Exception e) {
            LoggerHolder.get().error("releaseJson:" + releaseJson, e);
        }
        return "";
    }
    
    private static String findReleaseDigest(String version, String assetName) throws IOException {
        byte[] response = readURI(URI.create(RELEASES + "v" + version));
        try (JsonReader reader = Json.createReader(new ByteArrayInputStream(response))) {
            JsonObject release = reader.readObject();
            JsonArray assets = release.getJsonArray("assets");
            if (assets == null) {
                throw new IOException("Release asset list is missing");
            }
            for (var item : assets) {
                JsonObject asset = item.asJsonObject();
                if (assetName.equals(asset.getString("name", ""))) {
                    String digest = asset.getString("digest", "");
                    if (!digest.matches("sha256:[0-9a-fA-F]{64}")) {
                        throw new IOException("Release SHA-256 digest is missing or invalid");
                    }
                    return digest.substring("sha256:".length()).toLowerCase(java.util.Locale.ROOT);
                }
            }
            throw new IOException("Expected release archive not found: " + assetName);
        } catch (jakarta.json.JsonException | IllegalStateException e) {
            throw new IOException("Cannot parse Release metadata", e);
        }
    }

    private static void downloadUpdate(String url, Path destination, String expectedDigest) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Release download HTTP " + connection.getResponseCode());
            }
            java.security.MessageDigest digest;
            try {
                digest = java.security.MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IOException("SHA-256 unavailable", e);
            }
            try (InputStream in = connection.getInputStream();
                    var out = Files.newOutputStream(destination)) {
                byte[] buffer = new byte[16384];
                int size;
                long total = 0;
                while ((size = in.read(buffer)) != -1) {
                    total += size;
                    if (total > UpdateArchiveStage.MAX_BYTES) {
                        throw new IOException("Update download exceeds size limit");
                    }
                    digest.update(buffer, 0, size);
                    out.write(buffer, 0, size);
                }
                if (total == 0) {
                    throw new IOException("Empty update download");
                }
            }
            String actual = java.util.HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(expectedDigest)) {
                throw new IOException("Release SHA-256 mismatch");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void startUpdateProcess(String version, WebEngine webEngine) {

        Path targetDir;
        try {
            //MainClassであるLauncher.classの実行場所が、logbook-kai.jarのある場所
            targetDir = new File(Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toPath()
                .getParent();
        } catch (Exception e) {
            LoggerHolder.get().error("logbook-kai.jarの実行場所が見つかりませんでした" , e);
            return;
        }
        
        Map<WebEngine, Stage> logStages = new HashMap<WebEngine, Stage>();
        
        Task<String> updateTask = new Task<String>() {
            @Override
            protected String call() throws Exception {
                Path tempDir = Files.createTempDirectory("logbook-kai");
                try {
                    Path tempZip = tempDir.resolve("update.zip");
                    String assetName = "logbook-kai_" + version + ".zip";
                    String downloadURL = OPEN_URL + "/download/v" + version + "/" + assetName;

                    updateMessage("リリース情報を検証しています...");
                    String expectedDigest = findReleaseDigest(version, assetName);
                    updateMessage("ダウンロード中... " + downloadURL);
                    downloadUpdate(downloadURL, tempZip, expectedDigest);
                    updateMessage("ダウンロードとSHA-256検証が完了しました");

                    Path staged = UpdateArchiveStage.stage(tempZip, targetDir);
                    updateMessage("更新対象JARの検証と配置が完了: " + staged);
                    updateMessage("航海日誌を終了し、対応ランチャーから再起動してください。");
                    return "更新JARの検証と配置が完了しました。対応ランチャーで再起動してください。";
                } finally {
                    try (var files = Files.list(tempDir)) {
                        for (Path file : files.toList()) {
                            Files.deleteIfExists(file);
                        }
                    }
                    Files.deleteIfExists(tempDir);
                }
            }
        };

        updateTask.messageProperty().addListener((obs, oldMessage, newMessage) -> {
            Platform.runLater(() -> {
                Stage logStage = logStages.get(webEngine);
                if (logStage == null) {
                    ListView<String> listView = new ListView<>();
                    logStage = new Stage();
                    Stage mainStage = WindowHolder.getInstance().getMainWindow();
                    // 親ウィンドウの中央位置に表示
                    logStage.setX(mainStage.getX() + (mainStage.getWidth() - 600) / 2);
                    logStage.setY(mainStage.getY() + (mainStage.getHeight() - 400) / 2);
                    
                    logStage.setScene(new Scene(listView, 400, 300));
                    logStage.initOwner(mainStage);
                    logStage.setTitle("更新ログ");
                    
                    logStage.show();
                    logStages.put(webEngine, logStage);
                }
                @SuppressWarnings("unchecked")
                ListView<String> listView = (ListView<String>) logStage.getScene().getRoot();
                listView.getItems().add(newMessage);
            });
        });
        
        updateTask.setOnSucceeded(event -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("更新完了");
            alert.setContentText(updateTask.getValue());
            
            Stage logStage = logStages.get(webEngine);
            if (logStage != null) {
                alert.initOwner(logStage);
                alert.setX(logStage.getX());
                alert.setY(logStage.getY());
            }
            
            alert.showAndWait();
            
            // logStageを閉じる
            if (logStage != null) {
                logStage.close();
            }
        });

        updateTask.setOnFailed(event -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("更新失敗");
            alert.setContentText("エラーが発生しました: " + updateTask.getException().getMessage());

            Stage logStage = logStages.get(webEngine);
            if (logStage != null) {
                alert.initOwner(logStage);
                alert.setX(logStage.getX());
                alert.setY(logStage.getY());
            }
            
            alert.showAndWait();
        });

        new Thread(updateTask).start();
    }
}
