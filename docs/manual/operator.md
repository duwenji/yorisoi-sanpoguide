# SanpoGuide: 運営の手引き

[SanpoGuide の手引き](README.md) ＞ 運営の手引き

対象は SanpoGuide（散策ガイド）のアプリ側の運用です。yorisoi エコシステム全体や他のアプリの運用手順ではありません。

運営がアプリの側で受け持つ運用をまとめた手引きです。運営は当面、アプリの開発者本人が兼ねます。管理システムでの審査・承認・取り下げ・鍵の管理は、管理システムの[運営ガイド](https://github.com/duwenji/yorisoi-console/blob/main/docs/manual/operator.md)と[開発・保守ガイド](https://github.com/duwenji/yorisoi-console/blob/main/docs/manual/maintainer.md)にあります。

この手引きに出てくるファイルのパスとコマンドは、断りがなければ [yorisoi-sanpoguide](https://github.com/duwenji/yorisoi-sanpoguide) のリポジトリの中のものです。

## 目次

1. [運営がアプリの側で受け持つこと](#1-運営がアプリの側で受け持つこと)
2. [定期の確認](#2-定期の確認)
3. [問い合わせ・通報を受けたとき](#3-問い合わせ通報を受けたとき)
4. [組み込みのチャンネル・プロンプトを変えるとき](#4-組み込みのチャンネルプロンプトを変えるとき)
5. [アプリを公開・更新する](#5-アプリを公開更新する)
6. [リストをアプリで確かめる](#6-リストをアプリで確かめる)
7. [障害のときの判断](#7-障害のときの判断)
8. [情報の扱いを変えるとき](#8-情報の扱いを変えるとき)

## 1. 運営がアプリの側で受け持つこと

| 受け持つこと | 理由 |
|---|---|
| 問い合わせ・通報の窓口 | 組み込みのチャンネルとアプリの問い合わせ先、リストの `contact` が、どちらも運営に届く |
| 組み込みのチャンネルの確認 | 組み込みのチャンネルは管理システムの審査を通らず、アプリの版として届く（platform AI-ROLES H-13） |
| 枠の直し | 共通の指示（`shared/guard`）や出力の確認をすり抜けた AI の発言は、アプリで直す（AI-ROLES H-7） |
| アプリの公開と更新 | Google Play、署名の鍵、申告、プライバシーポリシー |
| リストの受け入れ | アプリに内蔵するリストと、そのリストIDの正しさ |
| スタジオの対応 | スタジオを出す素材提供者の承認と、通報された一言・スタジオへの対応は管理システムで行う（console の[運営ガイド 13 章](https://github.com/duwenji/yorisoi-console/blob/main/docs/manual/operator.md#13-スタジオ)）。アプリの通報は運営の画面の列に入り、メールでは届かない |

## 2. 定期の確認

| 間隔 | 確かめること | 方法 |
|---|---|---|
| 毎日 | 窓口のメール（duwenji@gmail.com）に通報・権利の申し立てが届いていないか | [3 章](#3-問い合わせ通報を受けたとき)の目安で対応する |
| 毎日 | リストの期限（発行から 14 日）が近づいていないか | 管理システムの「概要」（運営ガイド 3 章） |
| 毎週 | Play Console のクラッシュ・ANR・レビュー | Play Console の「品質」 |
| 毎週 | SanpoGuide の main の CI が通っているか | GitHub Actions の CI（`test lint assembleRelease`） |
| 毎週 | スポットのタイルが週に 1 回作り直されているか | 管理システムの[スポットのタイルの手順書](https://github.com/duwenji/yorisoi-console/blob/main/docs/operations/spot-tiles.md) |
| 月ごと | 公開サーバー（Overpass・OSRM）の失敗が増えていないか | 開発者モードの散歩の記録（`spot_search` の `source`・`overpass`）か、実機で `adb logcat -s Overpass` を見る。増えていれば自前・商用のサーバーを検討する |
| 半年ごと | 署名鍵の入れ替え | 運営ガイド 5.3 |
| 公開の前 | 申告・ポリシーとアプリの動きが合っているか | [8 章](#8-情報の扱いを変えるとき) |

## 3. 問い合わせ・通報を受けたとき

散策者が「この発言を知らせる」「問題を知らせる」を押すと、メールの下書きが開き、散策者が自分で送ります。アプリからは何も自動で送りません。

| 届く先 | 何が |
|---|---|
| duwenji@gmail.com（`Report.APP_CONTACT`） | 組み込みのチャンネルとその発言、`contact` のないリストのチャンネル |
| リストの `contact`（開発用・本番とも duwenji@gmail.com） | そのリストのチャンネルとその発言 |

### メールの読み方

件名は「AI の発言について（チャンネル名）」か「チャンネルについて（チャンネル名）」。本文の区切り線の下に次が入ります。位置は入りません。

```text
チャンネル: 歴史探訪（builtin:history@1）
リスト: （第三者のチャンネルのときだけ）
発言の時刻: 2026-10-17 14:05
発言: （AI の発言そのもの）
アプリ: SanpoGuide 1.0.0
```

### 原因で振り分ける

| 原因 | 例 | 直す人 | すること |
|---|---|---|---|
| 演出（チャンネル提供者） | 語り口が不快、話題が説明と違う | チャンネル提供者 | 運営ガイド 7 章の取り下げ・修正の依頼 |
| 根拠（素材提供者） | 素材の誤り、権利の侵害 | 素材提供者 | 同上。権利の申し立ては審査基準の流れで |
| 枠（アプリ） | 個人情報を尋ねた、危険な場所に誘った、URL を言った | アプリの開発者 | 下の「枠を直す」 |
| 組み込みのチャンネル | 組み込みのチャンネルの語りの誤り | アプリの開発者 | [4 章](#4-組み込みのチャンネルプロンプトを変えるとき)の手順で直して版を出す |
| AI のモデルの誤り | 事実の誤り（年代・由来） | — | 散策者に誤りがありうると示している。同じ誤りが多ければプロンプトの指示を見直す |

| 届いたもの | 目安 |
|---|---|
| 重い通報（安全を損なう誘い・違法・個人情報の収集） | 24 時間以内 |
| 軽い通報、枠をすり抜けた AI の発言 | 7 日以内 |
| 権利の申し立て | 受け付けの返信 3 日・判断 14 日 |

通報した人には返信しません。権利の申し立てだけは、受け付けと判断を申し立てた人に知らせます。

### 枠を直す

| すり抜けたもの | 直す場所 |
|---|---|
| AI への共通の指示（尋ねない・誘わない・勧めない） | `prompts/src/main/resources/sanpoguide/prompts/shared/guard.md` |
| 出力の確認（URL・メールアドレス・電話番号を読み上げない） | `app/.../guide/OutputCheck.kt` |
| 安全の案内のしきい値 | `app/.../settings/Threshold.kt` の既定値 |

直したら、プロンプト・パックの版を上げて公開し（[4 章](#4-組み込みのチャンネルプロンプトを変えるとき)）、アプリの版を出します（[release.md](https://github.com/duwenji/yorisoi-sanpoguide/blob/main/docs/operations/release.md)）。管理システムも同じプロンプト・パックで審査の見本を作るので、管理システムの依存も上げます。

## 4. 組み込みのチャンネル・プロンプトを変えるとき

組み込みのチャンネルは `prompts/src/main/resources/sanpoguide/channels/{id}/`、プロンプトは `prompts/src/main/resources/sanpoguide/prompts/` にあります。書き方は [prompts.md](https://github.com/duwenji/yorisoi-sanpoguide/blob/main/docs/design/prompts.md)、チャンネルの項目は [channels.md](https://github.com/duwenji/yorisoi-sanpoguide/blob/main/docs/design/channels.md)。

1. ブランチを切って直す
2. 手元で確かめる

   ```sh
   ./gradlew :prompts:test :app:testDebugUnitTest
   ```

   - 送るプロンプトが変わると `PromptGoldenTest` が落ち、今の出力が `prompts/build/prompt-golden.txt` に書き出される。差分が意図どおりなら `prompts/src/test/resources/prompt-golden.txt` に写す
   - `app/build/prompt-samples/{チャンネル}/` に全場面のプロンプトが書き出されるので、見比べる
3. チャンネルを変えたときは、第三者と同じ基準で確かめる（AI を呼ぶので CI では回さない）
   1. 変えたチャンネルのフォルダの中身（`channel.json` と `prompts/`）を ZIP にする
   2. 管理システムのチャンネル提供者の画面の「新しい版を申請する」でその ZIP を選び、「AI の一次審査」を実行する（申請はしない。キーは自分のもの）
   3. 挙がった点を直すか、直さない理由を PR に書く
4. PR を出し、CI が通ったらマージする
5. プロンプト・パックを公開する: `gradle.properties` の `sanpoguidePromptsVersion` を上げ、タグ `prompts-v<版>` を push する（GitHub Actions の Publish が GitHub Packages に出す。使う yorisoi の版を先に公開しておく）
6. アプリの版を出す（[release.md](https://github.com/duwenji/yorisoi-sanpoguide/blob/main/docs/operations/release.md)）。管理システムの依存も同じ版に上げる

## 5. アプリを公開・更新する

アプリだけの手続きなので、SanpoGuide の [release.md](https://github.com/duwenji/yorisoi-sanpoguide/blob/main/docs/operations/release.md)にあります（版を出すとき、Play Console の初回の登録、不具合の版を出してしまったとき）。

## 6. リストをアプリで確かめる

本番リストの有無と、リスト操作が表示される条件は、更新日を付けた[現在の公開状態](README.md)を参照してください。

### リストを公開した・変えたとき

1. アプリを開発者モードにする（設定の「バージョン」を 7 回タップ）
2. 設定の「チャンネル・リストと試用チケット」→「チャンネル・リストを足す」で URL とリストIDを入れる
3. 「今すぐ確かめる」を押し、「最後に確かめた日時」とリストの期限が出るか、チャンネルが一覧に出るかを見る
4. チャンネルを「取り込んで使う」で取り込み、AI の費用の目安の確認が出るか、切り替えられるかを見る
5. 「問題を知らせる」で、宛先がリストの `contact` になっているかを見る

外から形式を確かめるときは、管理システムの準拠テスト（開発・保守ガイド 6 章）を使います。

### 第三者のチャンネルを一般に開くとき

次がそろってから、ボタンをだれにでも出す版を作ります。

- 管理システムの本番（本番のアカウント・本番のルート鍵・独自ドメイン）
- 本番のリストの URL とリストID をアプリに内蔵する
- プライバシーポリシーの「第三者のチャンネル」（リストの発行元）を本番に合わせて直す
- 素材提供者の「確認済み」の印（MATERIALS 未決事項 No.4）

## 7. 障害のときの判断

| 起きていること | 散策者への影響 | すること |
|---|---|---|
| 管理システム・公開 URL が止まった | アプリは最後に受け取ったリストで期限（最長 14 日）まで動く。新しいチャンネルは取り込めない | 管理システムの開発・保守ガイド 10 章。期限までに直す |
| リストの期限が切れた | そのリストのチャンネルは使えなくなる（組み込みのチャンネルは使える） | 管理システムで作り直す（運営ガイド 4 章） |
| 署名鍵の漏えいの疑い | — | 運営ガイド 11 章。新しい鍵セットがアプリに届くまで最大 24 時間 |
| AI サービスの障害 | 解説と話しかけが定型文になる。安全の案内は続く | アプリでは何もしなくてよい。長く続くなら散策者に別のサービスを勧める |
| スポットのタイルの配信が止まった | 端末に残っているタイルを 90 日まで使う。残っていない場所は Overpass で探す | 管理システムのスポットのタイルの手順書。直るまで Overpass が予備になる |
| Overpass の公開サーバーが混んでいる | タイルが取れない場所で、スポットが出ない・遅い。予備のサーバーに切り替える（overpass-api.de → overpass.private.coffee → maps.mail.ru） | 一時的なら待つ。続くならサーバーの見直し |
| OSRM が使えない | ルートが方向の点線になる | 待つ |
| Open-Meteo が使えない | 天気の急変の案内が出ない | 待つ。続くなら別の天気のサービスを検討する |
| 地理院の地図が出ない | 地図が空白になる | 待つ。Google の地図は散策者のキーがあるときだけ |

## 8. 情報の扱いを変えるとき

アプリが外に送る情報・権限・保存するものを変えたら、同じ PR で次をそろえます。

| 文書 | 直すところ |
|---|---|
| [privacy-policy.md](https://github.com/duwenji/yorisoi-sanpoguide/blob/main/docs/policies/privacy-policy.md) | 外部サービスへの送信、位置情報、端末に保存する情報 |
| [play-console.md](https://github.com/duwenji/yorisoi-sanpoguide/blob/main/docs/operations/play-console.md) | データセーフティ、フォアグラウンドサービスの申告。マージ後に Play Console の答えも直す |
| [README](https://github.com/duwenji/yorisoi-sanpoguide/blob/main/README.md) | 「散歩の記録の扱い」「外部サービス」 |
| [散策者の手引き 12 章](walker.md#12-送る情報と費用) | 送る情報の表 |
