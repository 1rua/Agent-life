# KeystoreEncryptedNoBackupState 真机加密修复提案（未应用，待用户确认）

组件：`apps/android/tailnet-core/src/main/kotlin/com/agentlife/tailnet/core/KeystoreEncryptedNoBackupState.kt`

真机现象（SM-X710 / API 36）：
`Cipher.init(ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))` 抛
`InvalidAlgorithmParameterException: Caller-provided IV not permitted`。
原因：Android Keystore 生成密钥时 `RandomizedEncryptionRequired` 默认 true，
拒绝加密阶段由调用方提供 IV。

两个候选修复对现有信封格式（`iv(12B) || ciphertext(GCM-128 tag 内置)`）均无破坏。
务必保持：IV 每写一次随机、AES-256-GCM、TAG 128bit、原子临时文件重命名、无 backup。

## 方案②（推荐）：加密阶段由 Keystore 自生成 IV

只改 `write()` 两行：`init` 不传参数，随后从 `cipher.iv` 取回 IV 写信封。
```kotlin
override fun write(value: ByteArray) {
    require(value.isNotEmpty()) { "node state must not be empty" }
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key()) // Keystore 自生成随机 IV
    val iv = cipher.iv                          // 写回信封，保持兼容
    require(iv.size == IV_SIZE) { "keystore IV size unexpected" }
    val ciphertext = cipher.doFinal(value)
    val envelope = ByteArray(IV_SIZE + ciphertext.size)
    iv.copyInto(envelope, 0)
    ciphertext.copyInto(envelope, IV_SIZE)
    ...（其余不变：临时文件→renameTo）
}
```
优点：保留 `RandomizedEncryptionRequired(true)`（更安全默认）；decrypt 路径不变。

## 方案①：关掉 randomized-encryption 要求以允许调用方 IV

只改 `KeystoreEncryptedNoBackupState.key()` 一行：
```kotlin
.setRandomizedEncryptionRequired(false)
```
优点：改动最小（密钥生成参数），现有 write() 不用动。
代价：密钥明确允许调用方 IV —— 对“内部单点加解密、IV 仍各自随机”的现状等效，
但放弃了一个更强默认值。

## 动作项（等待批复）

- 用户确认“方案②”或“方案①”（或否）。
- 批准后：落到源码 → 重跑
  `:tailnet-core:connectedDebugAndroidTest` 的
  `P0tNodeStateProcessDeathInstrumentedTest#keystoreEncryptedNodeStateSurvivesFreshInstance`，
  目标将是 tailnet-core 12/12，并把本 proposal 归档为 applied。
