# 编译包的来源记录核对

manifest schema仍2，TTS模型API为2。source.build只含固定recipeId与包内provenancePath，不携带可执行安装命令。来源记录是安装前核验的数据。

新增正文核对：schema/recipeId与清单一致；固定官方Qwen模型来源和source revision；审计过的源码revision；已归档recipe path/SHA-256；ORT1.24.2；输入非空、相对路径/大小/hash有效；outputs必须精确覆盖清单除记录自身的所有文件，逐项大小/hash/角色/externalData一致。记录不hash自身，不执行其中任何字符串。文件完整性先验证，记录限制1MiB，重复键/非有限JSON拒绝。

Python fetch在包切换前检查，validate_models在native任务前检查；Kotlin probe/install和Swift probe/install接入同一规则。新增shared/build-provenance-fixtures.json共21例。Python与真实Swift core-checks全部通过；root另用缓存的Kotlin 2.1.0编译器直接编译生产ModelManifests.kt并运行21样例，全过（没有运行Gradle或占用AVD）。Gradle/Android集成仍归Android lane最终串行验收。

这是来源记录和已安装字节的一致性检查，不是独立证明模型质量或源代码实际执行的签名。实际导出源码快照、依赖和产物重建结果由compiler报告提供；shared包仍draft。
