# アプリの公開と更新

散策ガイドを Google Play に公開し、版を上げて出すときの手順。運営がアプリの側で受け持つほかの運用（通報の受け方、組み込みのチャンネルの確認、障害のときの判断など）は、[SanpoGuide 運営の手引き](https://github.com/duwenji/yorisoi-platform/blob/main/docs/apps/sanpoguide/manual/operator.md)を参照する。

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

## API で出す

手順 4 の AAB のアップロードと、ストアの説明・データ セーフティの書き換えは、Google Play Developer API を使う `tools/play.mjs` でもできる（Node.js の標準のモジュールだけ）。アプリのアクセス権など、ほかのアプリのコンテンツは API がないので Play Console で入れる。

```sh
node tools/play.mjs status                        # トラックとリリースの一覧（鍵の確認にも使う）
node tools/play.mjs listing                       # --listing で送る説明文（play-console.md から）
node tools/play.mjs edit --aab release/yorisoi-sanpoguide-1.3.0.aab --notes "リリースノート" --listing
node tools/play.mjs edit --aab release/yorisoi-sanpoguide-1.3.0.aab --notes "リリースノート" --listing --commit
node tools/play.mjs data-safety docs/play-data-safety.csv
```

- `edit` は `--commit` がなければ Play に確かめさせるだけで、何も変えない。`--commit` で審査に出る。Play Console で入れて審査に出していない変更も一緒に出る
- トラックは既定で内部テスト。`--track alpha`（クローズドテスト）・`production` も選べる
- `data-safety` は、Play Console のデータ セーフティの画面で書き出す CSV と同じ形式のファイルで、答えをすぐにまとめて置き換える（審査はアプリの変更と別に走る）

### サービス アカウントの準備（最初の 1 回）

1. Google Cloud で、プロジェクトを選ぶか作る（Play Integrity にも同じプロジェクトを使う）
2. [API とサービス] > [ライブラリ] で **Google Play Android Developer API** を有効にする
3. [IAM と管理] > [サービス アカウント] でサービス アカウントを作る（Google Cloud の役割は要らない）。[鍵] > [鍵を追加] > [新しい鍵を作成] で JSON の鍵を作る
4. 鍵を `~/.yorisoi-sanpoguide/play-service-account.json` に置く（別の場所なら `PLAY_SERVICE_ACCOUNT` で渡す）。Git には入れない
5. Play Console の **[ユーザーと権限] > [新しいユーザーを招待]** で、サービス アカウントのメールアドレスを招待する。[アプリの権限] で散策ガイドを足し、「テストトラックへのアプリのリリース」「本番環境へのアプリのリリース、端末の除外、Play App Signing の使用」「ストアでの表示の管理」「アプリ情報の閲覧」と、データ セーフティを書き換えるための「ポリシー申告の管理」を付ける（Play Console の表示名が違うときは、近い名前のものを選ぶ）
6. `node tools/play.mjs status` で一覧が出れば使える。招待の直後は権限が届くまで時間がかかることがある

## Play Console の登録

初回の登録、送る情報を変えたときの申告、Play Console のどこに何を入れるか、審査への出し方は [play-console.md](play-console.md) にある。

残っている作業は [TODO「Google Play での公開」](TODO.md#google-play-での公開)にある。

## テスターへの案内

テスターに送るのは、トラックの **[テスター]** タブにある参加用の URL（「ウェブで参加」）。メールアドレスのリストは、作ったうえでトラックでチェックを入れて保存する。新しいアプリでは、テストのリリースも審査を通るまでテスターに見えない（[公開の概要] で確かめる）。

テスターには次を伝える。

1. 参加用の URL を開き、テスターになる
2. 表示されるリンクを、ブラウザではなく **Play ストアのアプリ**で開いてインストールする。ブラウザで押すと端末への遠隔のインストールになり、OPPO などでは進まないことがある。ブラウザで開いてしまうときは https://play.google.com/store/apps/details?id=io.github.duwenji.yorisoi.sanpoguide を長押しして Play ストアで開く。内部テストのアプリは検索に出ない
3. 端末の Play ストアのアカウントを、テスターとして登録したアカウントにする
4. APK で入れた散策ガイドが残っていたら、先にアンインストールする（Play の版とは署名が違い、上書きできない。端末の記録と API キーは消える）
5. 「アイテムが見つかりませんでした」と出るときは、2〜3 を確かめ、しばらく待ってから開き直す

内部テストのテスターは、有料のアプリも無料で入れられる。クローズドテスト・オープンテストのテスターは購入が要る。

## 不具合の版を出してしまったとき

| 事態 | すること |
|---|---|
| 段階的な公開の途中 | Play Console で公開を止める（ロールアウトの停止） |
| すでに全員に出た | 前の版のコードで `versionCode` を上げた版を作り、出し直す（同じ `versionCode` には戻せない） |
| 組み込みのチャンネルの語りだけが問題 | 該当のチャンネルを直した版を急いで出す。第三者のチャンネルのような取り下げの仕組みはない |
