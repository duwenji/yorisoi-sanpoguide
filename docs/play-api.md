# Google Play Developer API で出す

Play Console の画面の操作の一部を、`tools/play.mjs` から Google Play Developer API で行うしくみと、その準備・使い方。版を出す流れ全体は [release.md](release.md)、Play Console に入れる答えは [play-console.md](play-console.md) にある。

## API でできること

| できる | できない（Play Console の画面で入れる） |
|---|---|
| AAB をトラックに上げ、リリースノートを付ける | アプリのアクセス権・広告・コンテンツのレーティングなど、データ セーフティ以外のアプリのコンテンツ |
| ストアのアプリ名・説明文・スマートフォンのスクリーンショット・連絡先を [play-console.md](play-console.md#ストアの掲載情報) と `store/` にそろえる | アイコン・フィーチャー グラフィック |
| データ セーフティの答えを CSV で置き換える | 価格・公開する国・テスターのリスト |
| 審査に出す | 段階的な公開の割合の変更・停止 |

## しくみ

### 登場するもの

| もの | 役割 |
|---|---|
| Google Cloud のプロジェクト | API を使う単位。どの API を有効にしたかを持つ。Play Integrity にも同じプロジェクトを使う |
| Google Play Android Developer API | Play Console の操作をプログラムから行う窓口（`androidpublisher`）。似た名前の Reporting API・Integrity API とは別 |
| サービス アカウント | プログラム用の Google アカウント。`名前@プロジェクトID.iam.gserviceaccount.com` のメールアドレスを持つ |
| JSON の鍵 | `tools/play.mjs` がサービス アカウントとして名乗るための秘密鍵。パスワードと同じに扱う |
| Play Console の招待と権限 | サービス アカウントが、どのアプリで何をしてよいかを決める |

運営者の Google アカウントはログインに 2 段階認証が要り、プログラムから使うには向かない。そこでプログラム専用のサービス アカウントを作り、散策ガイドのリリースと掲載情報に要る権限だけを渡す。鍵が漏れたときの影響はその権限の中に収まり、鍵を消すか招待を外せば止められる。

### 認証と認可の分担

- **誰か（認証）** は Google Cloud が受け持つ。プロジェクト・サービス アカウント・鍵がこれにあたる
- **何をしてよいか（認可）** は Play Console が受け持つ。招待と権限がこれにあたる

このため、Google Cloud ではサービス アカウントに役割（ロール）を付けず、Play Console で権限を付ける。

### 呼び出しの流れ

```text
tools/play.mjs
  │ ① 鍵で署名した JWT で、サービス アカウントとして名乗る
  ▼
Google の認証サーバー ── ② 1 時間だけ使えるアクセス トークンを返す
  │
  ▼ ③ トークンを付けて API を呼ぶ
Google Play Android Developer API
  │ ④ Play Console の権限で、散策ガイドへの操作が許されているかを確かめる
  ▼
Play Console に反映される
```

AAB のアップロードと掲載情報の変更は、「編集（edit）」の中で行う。編集を作り、変更を積み、最後に確定（commit）すると、まとめて審査に出る。確定しない編集は捨てられ、何も変わらない。データ セーフティは編集を使わず、すぐに置き換わる。

## 準備（最初の 1 回）

### 1. API を有効にする

1. https://console.cloud.google.com/ を、Play Console と同じ Google アカウントで開く
2. 上部のプロジェクト選択で、使うプロジェクトを選ぶか [新しいプロジェクト] で作る
3. **[API とサービス] > [ライブラリ]** で `Google Play Android Developer API` を検索して開き、**[有効にする]** を押す（https://console.cloud.google.com/apis/library/androidpublisher.googleapis.com ）。支払い情報は要らない

### 2. サービス アカウントと鍵を作る

1. **[IAM と管理] > [サービス アカウント]** で **[サービス アカウントを作成]** を押す
2. 名前（例: `play-publisher`）を入れて [作成して続行]。ロールの付与とユーザーへのアクセス権の付与は飛ばし、[完了]
3. 作ったサービス アカウントを開き、メールアドレスを控える
4. **[鍵]** タブの **[鍵を追加] > [新しい鍵を作成]** で JSON を選び、鍵をダウンロードする

### 3. 鍵を置く

鍵を `~/.yorisoi-sanpoguide/play-service-account.json`（Windows では `C:\Users\<ユーザー>\.yorisoi-sanpoguide\play-service-account.json`）に置く。別の場所に置くときは、環境変数 `PLAY_SERVICE_ACCOUNT` でパスを渡す。鍵は Git に入れず、人に送らない。ダウンロード フォルダに残った写しは消す。

### 4. Play Console に招待する

1. Play Console の、アプリを選ぶ前の画面で **[ユーザーと権限] > [新しいユーザーを招待]** を開く
2. 2 で控えたメールアドレスを入れる
3. **[アプリの権限]** で散策ガイドを足し、次の権限を付ける（表示名が違うときは近い名前のものを選ぶ）
   - アプリ情報の閲覧
   - テストトラックへのアプリのリリース
   - 本番環境へのアプリのリリース、端末の除外、Play App Signing の使用
   - ストアでの表示の管理
   - ポリシー申告の管理（データ セーフティの書き換えに使う）
4. [ユーザーを招待] を押す。サービス アカウントには招待のメールは届かず、承認も要らない

Play Console の [設定] > [API アクセス] でプロジェクトをつなぐ手順は、今は要らない。

### 5. 確かめる

```sh
node tools/play.mjs status
```

トラックとリリースの一覧が出れば使える。

## 使い方

```sh
node tools/play.mjs status                        # トラックとリリースの一覧
node tools/play.mjs listing                       # --listing で送るアプリ名と説明文（play-console.md から）
node tools/play.mjs edit --aab release/yorisoi-sanpoguide-1.3.0.aab --notes "リリースノート" --listing
node tools/play.mjs edit --aab release/yorisoi-sanpoguide-1.3.0.aab --notes "リリースノート" --listing --commit
node tools/play.mjs edit --version-code 5 --track alpha                  # 上げ済みの版をほかのトラックに出す
node tools/play.mjs edit --track SanpoGuide-CT --discard-drafts          # トラックの下書きを捨てる
node tools/play.mjs edit --listing --contact --screenshots               # ストアの掲載情報を文書と store/ にそろえる
node tools/play.mjs data-safety docs/play-data-safety.csv
```

- `--version-code` は、Play に上げ済みの版（同じ versionCode の AAB は二度上げられない）を別のトラックに出す。内部テストで確かめた版をクローズドテスト・本番に出すときに使う。`--notes` がなければ、その版がほかのトラックで持つリリースノートを写す
- `--discard-drafts` は、トラックの下書きのリリースを捨てる。公開済みのリリースは残る
- `--listing` は、[play-console.md の「ストアの掲載情報」](play-console.md#ストアの掲載情報)のアプリ名・簡単な説明・詳しい説明を送る
- `--contact` は、連絡先のウェブサイトを [play-console.md の「アプリの基本」](play-console.md#アプリの基本)にそろえ、電話番号を消す（入れるとストアで公開される）。メールアドレスは変えない
- `--screenshots` は、スマートフォンのスクリーンショットを `store/screenshots/` の PNG に、ファイル名の順で置き換える
- `edit` は `--commit` がなければ Play に確かめさせるだけで、何も変えない。まず `--commit` なしで通し、問題がなければ `--commit` を付けて審査に出す
- `--commit` で出ると、Play Console で保存して審査に出していない変更も一緒に出る
- トラックは既定で内部テスト（`internal`）。`--track alpha`（クローズドテスト）・`production`（本番）も選べる。API で出したリリースは段階的な公開にならず、トラックの全員に出る。本番に段階的に出すときは Play Console で出す
- `data-safety` に渡す CSV は、Play Console のデータ セーフティの画面の「CSV にエクスポート」と同じ形式（[play-console.md](play-console.md#データ-セーフティの入れ方)）。答えをすぐにまとめて置き換え、審査はアプリの変更と別に走る

## うまくいかないとき

| 症状 | 確かめること |
|---|---|
| `ENOENT` で鍵が読めない | 鍵の置き場所とファイル名、`PLAY_SERVICE_ACCOUNT` |
| `token: 400` / `401` | 鍵が消されていないか（Google Cloud の [鍵] タブ）。PC の時刻がずれていないか |
| `403` で API が有効でない | 1 の API を、鍵と同じプロジェクトで有効にしたか |
| `403` で権限がない | 4 の招待と権限。招待の直後は、権限が届くまで数時間（長いと 1 日）かかることがある |
| `edit` が versionCode で失敗する | 同じ `versionCode` の AAB はもう上げられない。`app/build.gradle.kts` で上げて作り直す（[release.md](release.md)） |
| 鍵の作成が組織のポリシーで止められる | 組織の管理下のプロジェクトでは、鍵の作成が無効のことがある。組織のないプロジェクトを使うか、管理者にポリシーを外してもらう |
