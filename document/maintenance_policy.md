# ドキュメント・実装メンテナンスポリシー

最終更新: 2026-05-06

## 1. 目的

本ポリシーは、GpsPressureLogger の仕様書、実装、作業記録、ToDo の更新ルールを定義する。

本プロジェクトでは、実装変更と文書変更を分離しない。

## 2. 更新対象

実装や修正の節目ごとに、次を必ず見直す。

- 外部仕様
- 実装仕様
- データ形式仕様
- 作業リスト
- ToDo リスト

## 3. 外部仕様

外部仕様は、ユーザー視点の仕様を記述する。

内容:

- 使い方
- 機能仕様
- 画面や操作の意味
- import / export の利用方法
- 制約や注意点

主な文書:

- [functional_spec.md](C:\Users\kawata\AndroidStudioProjects\GpsPressureLogger\document\functional_spec.md)
- [import_export_spec.md](C:\Users\kawata\AndroidStudioProjects\GpsPressureLogger\document\import_export_spec.md)

## 4. 実装仕様

実装仕様は、再実装可能な粒度で内部構造を記述する。

内容:

- モジュール構成
- 各モジュールの役割
- 必要ならサブモジュール構成
- API 仕様
- アルゴリズム仕様
- パラメータや共有定数の管理方針

主な文書:

- [design_doc.md](C:\Users\kawata\AndroidStudioProjects\GpsPressureLogger\document\design_doc.md)
- [room_data_spec.md](C:\Users\kawata\AndroidStudioProjects\GpsPressureLogger\document\room_data_spec.md)

## 5. データ形式仕様

データ形式仕様は、交換形式・内部形式・保存形式の定義を記述する。

主な文書:

- [data_spec.md](C:\Users\kawata\AndroidStudioProjects\GpsPressureLogger\document\data_spec.md)
- [room_data_spec.md](C:\Users\kawata\AndroidStudioProjects\GpsPressureLogger\document\room_data_spec.md)
- [import_export_spec.md](C:\Users\kawata\AndroidStudioProjects\GpsPressureLogger\document\import_export_spec.md)

## 6. 作業リストと ToDo

### 6.1 作業リスト

- 実施したことを時系列で残す
- 日単位または週単位で区切る
- 何を変更したか、何を確認したかを書く

対象文書:

- [work_log.md](C:\Users\kawata\AndroidStudioProjects\GpsPressureLogger\document\work_log.md)

### 6.2 ToDo リスト

- 未着手または継続課題のみ残す
- 完了したら削除する
- 「今後やるべきこと」が残っている状態を維持する

対象文書:

- [todo.md](C:\Users\kawata\AndroidStudioProjects\GpsPressureLogger\document\todo.md)

## 7. 仮説と確認

- 仮説を立てても、すぐにその仮説ベースで修正しない
- まずローカルコード、既存文書、必要に応じてネット等で確認する
- はっきり確定している内容は再確認不要

確認対象の例:

- 既存実装
- 既存仕様書
- 外部形式の公開仕様
- ライブラリ仕様

## 8. 定数とパラメータ

- 定数の直接記述はできる限り避ける
- 共通のしきい値、色、時間間隔、表示倍率は集約する
- 変更の可能性がある値はパラメータ化する

## 9. 共通化

- 共通機能は可能な限り 1 箇所へまとめる
- 同一責務のロジックを複数箇所に散らさない
- 表示用変換、マージルール、欠損判定などは統一実装を優先する

## 10. 定期的な見直し

- 一定期間または大きな節目ごとに、上記ルールが守られているか確認する
- 崩れてきた場合は、機能追加より先に構造修正を優先してよい

## 11. 運用ルール

今後の作業では、次を原則とする。

- 実装変更時は対応する仕様書を同時更新する
- 実装開始前に、対象仕様がどの文書に属するかを意識する
- 修正完了時に `work_log.md` と `todo.md` を更新する

