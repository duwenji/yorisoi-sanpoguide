# アプリの公開と更新

散策ガイドを Google Play に公開し、版を上げて出すときの手順。運営がアプリの側で受け持つほかの運用（通報の受け方、組み込みのチャンネルの確認、障害のときの判断など）は、yorisoi-platform の[運営の手引き](https://github.com/duwenji/yorisoi-platform/blob/main/docs/manual/operator.md)にある。

## 版を出すとき

1. `app/build.gradle.kts` の `versionCode` を 1 つ上げ、`versionName` を決める
2. リリースの AAB を作る

   ```sh
   ./gradlew bundleRelease
   ```

   署名には `keystore.properties` と `release.jks`（Play App Signing のアップロード鍵）を使う。どちらも Git に入れず、**別の場所にも写しを置く**（なくすと同じアプリとして更新できない）
3. エミュレータか実機で、少なくとも次を確かめる: 起動、位置の許可、散策の開始と終了（通知・画面を閉じたときの案内）、チャンネルの切り替え、設定の保存、記録
4. Play Console のトラック（内部テスト → クローズドテスト → 本番）に AAB を上げ、リリースノートを書く
5. 段階的な公開で出し、クラッシュが増えないかを見て広げる

## Play Console の初回の登録

| 項目 | 手順 |
|---|---|
| アプリのコンテンツ（データセーフティ・フォアグラウンドサービス・レーティング） | [play-console.md](play-console.md) の答えを入れる。フォアグラウンドサービスの申告の動画を撮る |
| プライバシーポリシー | https://duwenji.github.io/yorisoi-sanpoguide/privacy-policy（GitHub Pages、main の `/docs`。公開するのは privacy-policy.md だけ） |
| ストアの素材 | 512×512 のアイコン、1024×500 のフィーチャー画像、スクリーンショット、説明文 |
| クローズドテスト | 個人のデベロッパーアカウントは、本番の前に 12 人以上・14 日以上 |

残っている作業は [TODO「Google Play での公開」](TODO.md#google-play-での公開)にある。

## 不具合の版を出してしまったとき

| 事態 | すること |
|---|---|
| 段階的な公開の途中 | Play Console で公開を止める（ロールアウトの停止） |
| すでに全員に出た | 前の版のコードで `versionCode` を上げた版を作り、出し直す（同じ `versionCode` には戻せない） |
| 組み込みのチャンネルの語りだけが問題 | 該当のチャンネルを直した版を急いで出す。第三者のチャンネルのような取り下げの仕組みはない |

