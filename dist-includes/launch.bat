REM UTF-8対応
chcp 65001 >nul

SET JVM_OPT=-XX:MaxMetaspaceSize=256M

REM update/logbook-kai2.jar が存在するか確認
IF EXIST update\logbook-kai2.jar (
    echo update/logbook-kai2.jar を ./logbook-kai2.jar にコピーしています...
    COPY /Y update\logbook-kai2.jar logbook-kai2.jar
    echo コピーが完了しました。
) ELSE (
    echo update/logbook-kai2.jar が見つかりませんでした。コピーをスキップします。
)

REM アプリケーションを起動
START javaw %JVM_OPT% -jar logbook-kai2.jar
