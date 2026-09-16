# ローカルビルド

## Android APK

Android SDK (platform 34 / build-tools 34.0.0) と JDK 17 を用意し、`android/` の Java を通常の Gradle Android アプリとしてビルドしてください。署名は自分の keystore を使用します。

## 3DS CIA

devkitARM で `ctr/Makefile` を実行した後、Project_CTR `makerom` で次のようにパッケージします。

```text
makerom -f cia -o SaveBridgeMulti.cia \
  -elf ctr/SaveBridgeMulti.elf -rsf ctr/app.rsf \
  -icon ctr/SaveBridgeMulti.smdh -ver 3
```

CIA/APKは、暗号鍵・署名・端末固有のインストール環境に依存するため、配布用の秘密鍵はソースに含めていません。
