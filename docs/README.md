# SanpoGuide の文書

この `docs/` は Android アプリ **SanpoGuide** の動作・実装・公開・運用に関する文書です。ここに書かれた画面、機能、公開状況は SanpoGuide 固有であり、yorisoi エコシステム全体の方針や契約の正本ではありません。

## アプリを使う・公開する

| 知りたいこと | 文書 |
|---|---|
| アプリの機能と部品のつながり | [構造図](architecture.md) |
| 主な処理の呼び出しの順番 | [シーケンス図](sequences.md) |
| チャンネルのアプリ側での動作と実装 | [チャンネルの設計](channels.md) |
| AI に渡すプロンプトと変更方法 | [プロンプト一覧](prompts.md) |
| Google Play Console への申告内容 | [Play Console の申告](play-console.md) |
| 情報の収集・送信と端末内保存 | [プライバシーポリシー](privacy-policy.md) |
| アプリの公開・更新手順 | [アプリの公開と更新](release.md) |
| アプリ固有の残作業と検討事項 | [TODO](TODO.md) |

利用者別の詳しい操作・運用手順は [SanpoGuide 手引き](https://github.com/duwenji/yorisoi-platform/blob/main/docs/apps/sanpoguide/manual/README.md) を参照してください。

## エコシステム共通の正本

アプリをまたいで適用する方針、契約、用語は [yorisoi-platform](https://github.com/duwenji/yorisoi-platform) の文書が正本です。

| 知りたいこと | 正本 |
|---|---|
| エコシステムの目的とリポジトリの関係 | [エコシステム概要](https://github.com/duwenji/yorisoi-platform/blob/main/docs/overview/yorisoi-ecosystem.md) |
| 関係者の呼び方と共通用語 | [関係者と共通用語](https://github.com/duwenji/yorisoi-platform/blob/main/docs/overview/roles-and-vocabulary.md) |
| エコシステムの意思決定 | [ADR 一覧](https://github.com/duwenji/yorisoi-platform/blob/main/docs/decisions/README.md) |
| API・データ形式の契約 | [API 契約一覧](https://github.com/duwenji/yorisoi-platform/blob/main/docs/contracts/README.md) |
| チャンネルの共通審査方針 | [チャンネル審査方針](https://github.com/duwenji/yorisoi-platform/blob/main/docs/policies/channel-review.md) |
| エコシステム全体の作業 | [エコシステム TODO](https://github.com/duwenji/yorisoi-platform/blob/main/docs/work/ecosystem-todo.md) |

実装の現状はコードと設定を確認してください。アプリ固有の説明と共通契約が異なる場合、契約の形式・境界は platform の正本に従い、SanpoGuide での実装方法はこのリポジトリのコードと文書で説明します。
