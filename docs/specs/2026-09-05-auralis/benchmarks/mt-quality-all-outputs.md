# All 280 MT quality outputs

Fixed corpus; single-reviewer semantic findings. No failed output was dropped. Full inputs, raw stdout/stderr, exact commands and artifact hashes are in each linked results.json. All 280 calls returned status 0; runtime success does not mean semantic correctness.

| Run / variant | Cases with clear failures | Cases with subtle findings | Total |
|---|---:|---:|---:|
| mt-quality-q4 / native-no-context | 0 | 1 | 14 |
| mt-quality-q4 / native-context | 4 | 0 | 14 |
| mt-quality-q4 / sampling-no-context | 0 | 2 | 14 |
| mt-quality-q4 / sampling-context | 4 | 0 | 14 |
| mt-quality-q4-card-cli / card-cli-no-context | 0 | 3 | 14 |
| mt-quality-q4-card-cli / card-cli-context | 4 | 1 | 14 |
| mt-quality-q4-seed123 / sampling-no-context | 0 | 2 | 14 |
| mt-quality-q4-seed123 / sampling-context | 4 | 0 | 14 |
| mt-quality-q4-seed2026 / sampling-no-context | 0 | 2 | 14 |
| mt-quality-q4-seed2026 / sampling-context | 4 | 0 | 14 |
| mt-quality-stq / native-no-context | 1 | 1 | 14 |
| mt-quality-stq / native-context | 2 | 1 | 14 |
| mt-quality-stq / sampling-no-context | 2 | 1 | 14 |
| mt-quality-stq / sampling-context | 2 | 1 | 14 |
| mt-quality-stq-card-cli / card-cli-no-context | 2 | 1 | 14 |
| mt-quality-stq-card-cli / card-cli-context | 2 | 0 | 14 |
| mt-quality-stq-seed123 / sampling-no-context | 1 | 1 | 14 |
| mt-quality-stq-seed123 / sampling-context | 2 | 1 | 14 |
| mt-quality-stq-seed2026 / sampling-no-context | 2 | 1 | 14 |
| mt-quality-stq-seed2026 / sampling-context | 2 | 1 | 14 |

## mt-quality-q4 / native-no-context

[Raw results](mt-quality-q4/results.json)

| Case | Output | Review |
|---|---|---|
| museum | Let’s go to the museum this afternoon, okay? | No clear semantic error identified |
| unrelated-number | Please respond before Friday. | No clear semantic error identified |
| negation | I didn’t send the documents to Li Ming. | No clear semantic error identified |
| time-negation | 会议于下午3点开始，不是4点。 | No clear semantic error identified |
| name-number | 请在周五之前拨打138-0013-8000联系王医生。 | No clear semantic error identified |
| decimal-unit | The temperature increased from -5.5 degrees Celsius to 2 degrees Celsius. | No clear semantic error identified |
| unknown-source | 今天下着雨。 | No clear semantic error identified |
| japanese-source | 今天下着雨。 | No clear semantic error identified |
| language-codes | Please close the door. | No clear semantic error identified |
| proper-name | 前往苏黎世的列车于06:45从12号站台出发。 | No clear semantic error identified |
| female-pronoun | 她将于明天到达。 | No clear semantic error identified |
| bank-sense | 他坐在河岸上。 | No clear semantic error identified |
| context-time | 会议将在同一时间举行。 | NOTE: unsupported specificity: It becomes meeting without context |
| unknown-en | 请在周一之前不要打开包装好的B-17物品。 | No clear semantic error identified |

## mt-quality-q4 / native-context

[Raw results](mt-quality-q4/results.json)

| Case | Output | Review |
|---|---|---|
| museum | Let’s go to the museum this afternoon, how about that? | No clear semantic error identified |
| unrelated-number | Please reply before Friday. | No clear semantic error identified |
| negation | I didn’t send the files to Li Ming. | No clear semantic error identified |
| time-negation | 会议于下午 3 点开始，不是 4 点。 | No clear semantic error identified |
| name-number | 请在周五之前拨打 138-0013-8000 联系王医生。 | No clear semantic error identified |
| decimal-unit | The wind speed yesterday was 80 kilometers per hour. | FAIL: context leakage or replacement: translates preceding history; FAIL: source omission: requested temperature sentence absent |
| unknown-source | 今天下着雨呢。 | No clear semantic error identified |
| japanese-source | 昨天下雪了。  <br>今天有雨。 | FAIL: context leakage or replacement: translates preceding history |
| language-codes | Please close the door. | No clear semantic error identified |
| proper-name | 前往巴黎的火车已经取消运行了。  <br>前往苏黎世的车厢在 06:45 从 12 号站台出发。 | FAIL: context leakage or replacement: translates preceding history; FAIL: wrong lexical sense: train becomes carriage |
| female-pronoun | 陈医生明天会到达。 | No clear semantic error identified |
| bank-sense | 他坐在河岸上。 | No clear semantic error identified |
| context-time | 会议将在同一时间举行。 | No clear semantic error identified |
| unknown-en | 包裹A-12已于昨日被拆开。  <br>请不要在周一之前打开包裹B-17。 | FAIL: context leakage or replacement: translates preceding history |

## mt-quality-q4 / sampling-no-context

[Raw results](mt-quality-q4/results.json)

| Case | Output | Review |
|---|---|---|
| museum | Shall we go to the museum this afternoon? How about that? | No clear semantic error identified |
| unrelated-number | Please respond by Friday. | NOTE: deadline weakened: before Friday becomes by Friday |
| negation | I didn’t send the documents to Li Ming. | No clear semantic error identified |
| time-negation | 会议将在下午3点开始，而不是4点。 | No clear semantic error identified |
| name-number | 请在周五之前拨打138-0013-8000联系王医生。 | No clear semantic error identified |
| decimal-unit | The temperature rose from -5.5 degrees Celsius to 2 degrees Celsius. | No clear semantic error identified |
| unknown-source | 今天下着雨呢。 | No clear semantic error identified |
| japanese-source | 今天下着雨呢。 | No clear semantic error identified |
| language-codes | Please close the door. | No clear semantic error identified |
| proper-name | 前往苏黎世的列车于6点45分从12号站台出发。 | No clear semantic error identified |
| female-pronoun | 她将于明天到达。 | No clear semantic error identified |
| bank-sense | 他坐在岸边。 | No clear semantic error identified |
| context-time | 会议将在同一时间举行。 | NOTE: unsupported specificity: It becomes meeting without context |
| unknown-en | 请不要在周一之前打开包装好的B-17产品。 | No clear semantic error identified |

## mt-quality-q4 / sampling-context

[Raw results](mt-quality-q4/results.json)

| Case | Output | Review |
|---|---|---|
| museum | How about visiting a museum this afternoon? | No clear semantic error identified |
| unrelated-number | Please reply before Friday. | No clear semantic error identified |
| negation | I didn’t send the files to Li Ming. | No clear semantic error identified |
| time-negation | 会议将于下午 3 点开始，而不是 4 点。 | No clear semantic error identified |
| name-number | 请在周五之前拨打 138-0013-8000 联系王医生。 | No clear semantic error identified |
| decimal-unit | The wind speed yesterday was 80 kilometers per hour.  <br>According to the above information, the temperature rose from -5.5 degrees Celsius to 2 degrees Celsius. | FAIL: context leakage or replacement: translates preceding history |
| unknown-source | 今天下着雨。 | No clear semantic error identified |
| japanese-source | 昨天下着雪。 | FAIL: context leakage or replacement: translates preceding history; FAIL: source omission: requested current rain absent |
| language-codes | Please close the door. | No clear semantic error identified |
| proper-name | 前往巴黎的列车已经取消。  <br>开往苏黎世的车厢将于 6:45 从 12 号站台出发。 | FAIL: context leakage or replacement: translates preceding history; FAIL: wrong lexical sense: train becomes carriage |
| female-pronoun | 她明天会到达。 | No clear semantic error identified |
| bank-sense | 他坐在河岸上。 | No clear semantic error identified |
| context-time | 会议将在同一时间举行。 | No clear semantic error identified |
| unknown-en | 包裹A-12已于昨日被拆开。  <br>请不要在周一之前打开包裹B-17。 | FAIL: context leakage or replacement: translates preceding history |

## mt-quality-q4-card-cli / card-cli-no-context

[Raw results](mt-quality-q4-card-cli/results.json)

| Case | Output | Review |
|---|---|---|
| museum | Shall we go to the museum this afternoon? How about that? | No clear semantic error identified |
| unrelated-number | Please respond by Friday. | NOTE: deadline weakened: before Friday becomes by Friday |
| negation | I didn’t send the files to Li Ming. | No clear semantic error identified |
| time-negation | 会议将在下午3点开始，而不是4点。 | No clear semantic error identified |
| name-number | 请在周五之前拨打138-0013-8000联系王医生。 | No clear semantic error identified |
| decimal-unit | The temperature rose from -5.5 degrees Celsius to 2 degrees Celsius. | No clear semantic error identified |
| unknown-source | 今天下着雨呢。 | No clear semantic error identified |
| japanese-source | 今天下着雨呢。 | No clear semantic error identified |
| language-codes | Please close the door behind you. | NOTE: unsupported addition: behind you |
| proper-name | 前往苏黎世的火车在06:45从12号站台出发。 | No clear semantic error identified |
| female-pronoun | 她将于明天到达。 | No clear semantic error identified |
| bank-sense | 他坐在岸边。 | No clear semantic error identified |
| context-time | 会议将在同一时间举行。 | NOTE: unsupported specificity: It becomes meeting without context |
| unknown-en | 请不要在周一之前打开包装好的B-17物品。 | No clear semantic error identified |

## mt-quality-q4-card-cli / card-cli-context

[Raw results](mt-quality-q4-card-cli/results.json)

| Case | Output | Review |
|---|---|---|
| museum | How about visiting a museum this afternoon? | No clear semantic error identified |
| unrelated-number | Please reply before Friday. | No clear semantic error identified |
| negation | I didn’t send the files to Li Ming. | No clear semantic error identified |
| time-negation | 会议将于下午 3 点开始，而不是 4 点。 | No clear semantic error identified |
| name-number | 请在周五之前拨打 138-0013-8000 联系王医生。 | No clear semantic error identified |
| decimal-unit | The wind speed yesterday was 80 kilometers per hour.  <br>According to the above information, the temperature rose from -5.5 degrees Celsius to 2 degrees Celsius. | FAIL: context leakage or replacement: translates preceding history |
| unknown-source | 今天下着雨呢。 | No clear semantic error identified |
| japanese-source | 昨天下雪了。  <br>今天的天气是下雨。 | FAIL: context leakage or replacement: translates preceding history |
| language-codes | Please close the door. | No clear semantic error identified |
| proper-name | 前往巴黎的列车已经取消。  <br>开往苏黎世的车厢将于 06:45 从 12 号站台出发。 | FAIL: context leakage or replacement: translates preceding history; FAIL: wrong lexical sense: train becomes carriage |
| female-pronoun | 她明天会到达。 | No clear semantic error identified |
| bank-sense | 他坐在河岸上。 | No clear semantic error identified |
| context-time | 会议将与往常一样，在同一时间举行。 | NOTE: unsupported habitual implication: as usual |
| unknown-en | 包裹A-12已于昨日被打开。  <br>请不要在周一之前打开包裹B-17。 | FAIL: context leakage or replacement: translates preceding history |

## mt-quality-q4-seed123 / sampling-no-context

[Raw results](mt-quality-q4-seed123/results.json)

| Case | Output | Review |
|---|---|---|
| museum | Shall we go to the museum this afternoon? How about that? | No clear semantic error identified |
| unrelated-number | Please respond by Friday. | NOTE: deadline weakened: before Friday becomes by Friday |
| negation | I didn’t send the documents to Li Ming. | No clear semantic error identified |
| time-negation | 会议将在下午3点开始，而不是4点。 | No clear semantic error identified |
| name-number | 请在周五之前拨打138-0013-8000联系王医生。 | No clear semantic error identified |
| decimal-unit | The temperature rose from -5.5 degrees Celsius to 2 degrees Celsius. | No clear semantic error identified |
| unknown-source | 今天下着雨呢。 | No clear semantic error identified |
| japanese-source | 今天下着雨呢。 | No clear semantic error identified |
| language-codes | Please close the door. | No clear semantic error identified |
| proper-name | 前往苏黎世的列车于6点45分从12号站台出发。 | No clear semantic error identified |
| female-pronoun | 她将于明天到达。 | No clear semantic error identified |
| bank-sense | 他坐在岸边。 | No clear semantic error identified |
| context-time | 会议将在同一时间举行。 | NOTE: unsupported specificity: It becomes meeting without context |
| unknown-en | 请不要在周一之前打开包装好的B-17产品。 | No clear semantic error identified |

## mt-quality-q4-seed123 / sampling-context

[Raw results](mt-quality-q4-seed123/results.json)

| Case | Output | Review |
|---|---|---|
| museum | How about visiting a museum this afternoon? | No clear semantic error identified |
| unrelated-number | Please reply before Friday. | No clear semantic error identified |
| negation | I didn’t send the files to Li Ming. | No clear semantic error identified |
| time-negation | 会议将于下午 3 点开始，而不是 4 点。 | No clear semantic error identified |
| name-number | 请在周五之前拨打 138-0013-8000 联系王医生。 | No clear semantic error identified |
| decimal-unit | The wind speed yesterday was 80 kilometers per hour.  <br>According to the above information, the temperature rose from -5.5 degrees Celsius to 2 degrees Celsius. | FAIL: context leakage or replacement: translates preceding history |
| unknown-source | 今天下着雨。 | No clear semantic error identified |
| japanese-source | 昨天下着雪。 | FAIL: context leakage or replacement: translates preceding history; FAIL: source omission: requested current rain absent |
| language-codes | Please close the door. | No clear semantic error identified |
| proper-name | 前往巴黎的列车已经取消。  <br>开往苏黎世的车厢将于 6:45 从 12 号站台出发。 | FAIL: context leakage or replacement: translates preceding history; FAIL: wrong lexical sense: train becomes carriage |
| female-pronoun | 她明天会到达。 | No clear semantic error identified |
| bank-sense | 他坐在河岸上。 | No clear semantic error identified |
| context-time | 会议将在同一时间举行。 | No clear semantic error identified |
| unknown-en | 包裹A-12已于昨日被拆开。  <br>请不要在周一之前打开包裹B-17。 | FAIL: context leakage or replacement: translates preceding history |

## mt-quality-q4-seed2026 / sampling-no-context

[Raw results](mt-quality-q4-seed2026/results.json)

| Case | Output | Review |
|---|---|---|
| museum | Shall we go to the museum this afternoon? How about that? | No clear semantic error identified |
| unrelated-number | Please respond by Friday. | NOTE: deadline weakened: before Friday becomes by Friday |
| negation | I didn’t send the documents to Li Ming. | No clear semantic error identified |
| time-negation | 会议将在下午3点开始，而不是4点。 | No clear semantic error identified |
| name-number | 请在周五之前拨打138-0013-8000联系王医生。 | No clear semantic error identified |
| decimal-unit | The temperature rose from -5.5 degrees Celsius to 2 degrees Celsius. | No clear semantic error identified |
| unknown-source | 今天下着雨呢。 | No clear semantic error identified |
| japanese-source | 今天下着雨呢。 | No clear semantic error identified |
| language-codes | Please close the door. | No clear semantic error identified |
| proper-name | 前往苏黎世的列车于6点45分从12号站台出发。 | No clear semantic error identified |
| female-pronoun | 她将于明天到达。 | No clear semantic error identified |
| bank-sense | 他坐在岸边。 | No clear semantic error identified |
| context-time | 会议将在同一时间举行。 | NOTE: unsupported specificity: It becomes meeting without context |
| unknown-en | 请不要在周一之前打开包装好的B-17物品。 | No clear semantic error identified |

## mt-quality-q4-seed2026 / sampling-context

[Raw results](mt-quality-q4-seed2026/results.json)

| Case | Output | Review |
|---|---|---|
| museum | How about visiting a museum this afternoon? | No clear semantic error identified |
| unrelated-number | Please reply before Friday. | No clear semantic error identified |
| negation | I didn’t send the files to Li Ming. | No clear semantic error identified |
| time-negation | 会议将于下午 3 点开始，而不是 4 点。 | No clear semantic error identified |
| name-number | 请在周五之前拨打 138-0013-8000 联系王医生。 | No clear semantic error identified |
| decimal-unit | The wind speed yesterday was 80 kilometers per hour.  <br>According to the above information, the temperature rose from -5.5 degrees Celsius to 2 degrees Celsius. | FAIL: context leakage or replacement: translates preceding history |
| unknown-source | 今天下着雨。 | No clear semantic error identified |
| japanese-source | 昨天下着雪。 | FAIL: context leakage or replacement: translates preceding history; FAIL: source omission: requested current rain absent |
| language-codes | Please close the door. | No clear semantic error identified |
| proper-name | 前往巴黎的列车已经取消。  <br>前往苏黎世列车的出发站台是 12 号站台，发车时间为 06:45。 | FAIL: context leakage or replacement: translates preceding history |
| female-pronoun | 她明天会到达。 | No clear semantic error identified |
| bank-sense | 他坐在河岸上。 | No clear semantic error identified |
| context-time | 会议将在同一时间举行。 | No clear semantic error identified |
| unknown-en | 包裹A-12已于昨日被拆开。  <br>请不要在周一之前打开包裹B-17。 | FAIL: context leakage or replacement: translates preceding history |

## mt-quality-stq / native-no-context

[Raw results](mt-quality-stq/results.json)

| Case | Output | Review |
|---|---|---|
| museum | Let's go to the museum for a visit this afternoon, okay? | No clear semantic error identified |
| unrelated-number | Please reply by Friday. | NOTE: deadline weakened: before Friday becomes by Friday |
| negation | I didn’t send the file to Li Ming. | No clear semantic error identified |
| time-negation | 会议在下午3:00开始，而不是4:00。 | No clear semantic error identified |
| name-number | 请在周五之前拨打138-0013-8000联系王医生。 | No clear semantic error identified |
| decimal-unit | The temperature has risen from minus 5.5 degrees Celsius to 2 degrees Celsius. | No clear semantic error identified |
| unknown-source | 今天正在下雨。 | No clear semantic error identified |
| japanese-source | 今天正在下雨。 | No clear semantic error identified |
| language-codes | Please close the door. | No clear semantic error identified |
| proper-name | 从12号站台出发的前往苏黎世的火车将于06:45出发。 | No clear semantic error identified |
| female-pronoun | 她明天就会到达。 | No clear semantic error identified |
| bank-sense | 他坐在长椅上。 | FAIL: wrong lexical sense: bank translated as bench |
| context-time | 它将会同时举行。 | No clear semantic error identified |
| unknown-en | 请在周一之前不要打开B-17包裹。 | No clear semantic error identified |

## mt-quality-stq / native-context

[Raw results](mt-quality-stq/results.json)

| Case | Output | Review |
|---|---|---|
| museum | It’s nice weather today.  <br>Let’s go to the museum this afternoon, okay? | FAIL: context leakage: weather assertion |
| unrelated-number | Please reply by Friday. | NOTE: deadline weakened: before Friday becomes by Friday |
| negation | I didn’t send the file to Li Ming. | No clear semantic error identified |
| time-negation | 会议于下午3点开始，而不是4点。 | No clear semantic error identified |
| name-number | 请在周五之前拨打138-0013-8000联系王医生。 | No clear semantic error identified |
| decimal-unit | The temperature rose from minus 5.5 degrees Celsius to 2 degrees Celsius. | No clear semantic error identified |
| unknown-source | 今天会下雨。 | FAIL: aspect/tense shift: ongoing rain becomes future rain |
| japanese-source | 今天下着雨。 | No clear semantic error identified |
| language-codes | Please close the door. | No clear semantic error identified |
| proper-name | 前往苏黎世的火车将于06:45从12号站台出发。 | No clear semantic error identified |
| female-pronoun | 她明天就会到达。 | No clear semantic error identified |
| bank-sense | 他坐在河岸边。 | No clear semantic error identified |
| context-time | 会议将同时举行。 | No clear semantic error identified |
| unknown-en | B-17包装件在周一之前不要打开。 | No clear semantic error identified |

## mt-quality-stq / sampling-no-context

[Raw results](mt-quality-stq/results.json)

| Case | Output | Review |
|---|---|---|
| museum | Let's go to the museum this afternoon, okay? | No clear semantic error identified |
| unrelated-number | Please reply by Friday. | NOTE: deadline weakened: before Friday becomes by Friday |
| negation | I didn’t send the file to Li Ming. | No clear semantic error identified |
| time-negation | 会议于下午3点开始，而不是4点。 | No clear semantic error identified |
| name-number | 请在周五之前拨打138-0013-8000联系王医生。 | No clear semantic error identified |
| decimal-unit | The temperature has risen from minus 5.5 degrees Celsius to 2 degrees Celsius. | No clear semantic error identified |
| unknown-source | 今天正在下雨。 | No clear semantic error identified |
| japanese-source | 今天正在下雨。 | No clear semantic error identified |
| language-codes | Please close the door. | No clear semantic error identified |
| proper-name | 从机场12号站台出发的列车将于06:45出发前往苏黎世。 | FAIL: hallucinated location: airport absent from source |
| female-pronoun | 她明天就会到达。 | No clear semantic error identified |
| bank-sense | 他坐在长椅上。 | FAIL: wrong lexical sense: bank translated as bench |
| context-time | 它将会同时举行。 | No clear semantic error identified |
| unknown-en | 请在周一之前不要打开B-17包裹。 | No clear semantic error identified |

## mt-quality-stq / sampling-context

[Raw results](mt-quality-stq/results.json)

| Case | Output | Review |
|---|---|---|
| museum | Let’s visit the museum this afternoon, okay? | No clear semantic error identified |
| unrelated-number | Please reply by Friday. | NOTE: deadline weakened: before Friday becomes by Friday |
| negation | I didn’t send the file to Li Ming. | No clear semantic error identified |
| time-negation | 会议将于下午3点开始，而不是4点。 | No clear semantic error identified |
| name-number | 请在周五之前拨打138-0013-8000联系王医生。 | No clear semantic error identified |
| decimal-unit | The temperature rose from minus 5.5 degrees Celsius to 2 degrees Celsius. | No clear semantic error identified |
| unknown-source | 今天会下雨。 | FAIL: aspect/tense shift: ongoing rain becomes future rain |
| japanese-source | 今天下着雨。 | No clear semantic error identified |
| language-codes | Please close the door. | No clear semantic error identified |
| proper-name | 去苏黎世的火车将于6点45分从12号站台出发。 | No clear semantic error identified |
| female-pronoun | 她明天会到达。 | No clear semantic error identified |
| bank-sense | 他坐在河岸上。 | No clear semantic error identified |
| context-time | 会议将同时举行。 | No clear semantic error identified |
| unknown-en | 请将B-17包装在周一之前打开。 | FAIL: negation reversal: prohibition becomes instruction to open before Monday |

## mt-quality-stq-card-cli / card-cli-no-context

[Raw results](mt-quality-stq-card-cli/results.json)

| Case | Output | Review |
|---|---|---|
| museum | Let's go to the museum this afternoon, okay? | No clear semantic error identified |
| unrelated-number | Please reply by Friday. | NOTE: deadline weakened: before Friday becomes by Friday |
| negation | I didn’t send the file to Li Ming. | No clear semantic error identified |
| time-negation | 会议于下午3点开始，而不是4点。 | No clear semantic error identified |
| name-number | 请在周五之前致电138-0013-8000联系王医生。 | No clear semantic error identified |
| decimal-unit | The temperature has risen from minus 5.5 degrees Celsius to 2 degrees Celsius. | No clear semantic error identified |
| unknown-source | 今天正在下雨。 | No clear semantic error identified |
| japanese-source | 今天正在下雨。 | No clear semantic error identified |
| language-codes | Please close the door. | No clear semantic error identified |
| proper-name | 前往苏黎世的火车将于06:45从12号站台出发。 | No clear semantic error identified |
| female-pronoun | 她明天会到达。 | No clear semantic error identified |
| bank-sense | 他坐在长椅上。 | FAIL: wrong lexical sense: bank translated as bench |
| context-time | 它将会同时举行。 | No clear semantic error identified |
| unknown-en | 请务必在周一之前打开B-17包裹。 | FAIL: negation reversal: prohibition becomes instruction to open before Monday |

## mt-quality-stq-card-cli / card-cli-context

[Raw results](mt-quality-stq-card-cli/results.json)

| Case | Output | Review |
|---|---|---|
| museum | Today afternoon, shall we visit the museum? | No clear semantic error identified |
| unrelated-number | Please reply before Friday. | No clear semantic error identified |
| negation | I didn’t send the file to Li Ming. | No clear semantic error identified |
| time-negation | 会议将于下午3点开始，而不是4点。 | No clear semantic error identified |
| name-number | 请在周五之前拨打138-0013-8000联系王医生。 | No clear semantic error identified |
| decimal-unit | The temperature rose from minus 5.5 degrees Celsius to 2 degrees Celsius. | No clear semantic error identified |
| unknown-source | 今天会下雨。 | FAIL: aspect/tense shift: ongoing rain becomes future rain |
| japanese-source | 今天正在下雨。 | No clear semantic error identified |
| language-codes | Please close the door. | No clear semantic error identified |
| proper-name | 去苏黎世的火车将于6点45分从12号站台出发。 | No clear semantic error identified |
| female-pronoun | 她明天会到达。 | No clear semantic error identified |
| bank-sense | 他坐在河岸上。 | No clear semantic error identified |
| context-time | 会议将同时举行。 | No clear semantic error identified |
| unknown-en | 请将B-17包装在周一之前打开。 | FAIL: negation reversal: prohibition becomes instruction to open before Monday |

## mt-quality-stq-seed123 / sampling-no-context

[Raw results](mt-quality-stq-seed123/results.json)

| Case | Output | Review |
|---|---|---|
| museum | Let's go to the museum this afternoon, okay? | No clear semantic error identified |
| unrelated-number | Please reply by Friday. | NOTE: deadline weakened: before Friday becomes by Friday |
| negation | I didn’t send the file to Li Ming. | No clear semantic error identified |
| time-negation | 会议将于下午3点开始，而不是4点。 | No clear semantic error identified |
| name-number | 请在周五之前拨打138-0013-8000联系王医生。 | No clear semantic error identified |
| decimal-unit | The temperature has risen from minus 5.5 degrees Celsius to 2 degrees Celsius. | No clear semantic error identified |
| unknown-source | 今天正在下雨。 | No clear semantic error identified |
| japanese-source | 今天正在下雨。 | No clear semantic error identified |
| language-codes | Please close the door. | No clear semantic error identified |
| proper-name | 从站台12出发的前往苏黎世的火车将于06:45出发。 | No clear semantic error identified |
| female-pronoun | 她明天就会到达。 | No clear semantic error identified |
| bank-sense | 他坐在长椅上。 | FAIL: wrong lexical sense: bank translated as bench |
| context-time | 它将会同时举行。 | No clear semantic error identified |
| unknown-en | 请在周一之前不要打开B-17包裹。 | No clear semantic error identified |

## mt-quality-stq-seed123 / sampling-context

[Raw results](mt-quality-stq-seed123/results.json)

| Case | Output | Review |
|---|---|---|
| museum | Let’s visit the museum this afternoon, okay? | No clear semantic error identified |
| unrelated-number | Please reply by Friday. | NOTE: deadline weakened: before Friday becomes by Friday |
| negation | I didn’t send the file to Li Ming. | No clear semantic error identified |
| time-negation | 会议将于下午3点开始，而不是4点。 | No clear semantic error identified |
| name-number | 请在周五之前拨打138-0013-8000联系王医生。 | No clear semantic error identified |
| decimal-unit | The temperature rose from minus 5.5 degrees Celsius to 2 degrees Celsius. | No clear semantic error identified |
| unknown-source | 今天会下雨。 | FAIL: aspect/tense shift: ongoing rain becomes future rain |
| japanese-source | 今天下着雨。 | No clear semantic error identified |
| language-codes | Please close the door. | No clear semantic error identified |
| proper-name | 去苏黎世的火车将于6点45分从12号站台出发。 | No clear semantic error identified |
| female-pronoun | 她明天会到达。 | No clear semantic error identified |
| bank-sense | 他坐在河岸上。 | No clear semantic error identified |
| context-time | 会议将同时举行。 | No clear semantic error identified |
| unknown-en | 请务必在周一之前打开B-17包装。 | FAIL: negation reversal: prohibition becomes instruction to open before Monday |

## mt-quality-stq-seed2026 / sampling-no-context

[Raw results](mt-quality-stq-seed2026/results.json)

| Case | Output | Review |
|---|---|---|
| museum | Let's go to the museum this afternoon, okay? | No clear semantic error identified |
| unrelated-number | Please reply by Friday. | NOTE: deadline weakened: before Friday becomes by Friday |
| negation | I didn’t send the file to Li Ming. | No clear semantic error identified |
| time-negation | 会议于下午3点开始，而不是4点。 | No clear semantic error identified |
| name-number | 请在周五之前拨打138-0013-8000联系王医生。 | No clear semantic error identified |
| decimal-unit | The temperature has risen from minus 5.5 degrees Celsius to 2 degrees Celsius. | No clear semantic error identified |
| unknown-source | 今天正在下雨。 | No clear semantic error identified |
| japanese-source | 今天正在下雨。 | No clear semantic error identified |
| language-codes | Please close the door. | No clear semantic error identified |
| proper-name | 从机场12号站出发的列车将于06:45出发前往苏黎世。 | FAIL: hallucinated location: airport absent from source |
| female-pronoun | 她明天就会到达。 | No clear semantic error identified |
| bank-sense | 他坐在长椅上。 | FAIL: wrong lexical sense: bank translated as bench |
| context-time | 它将会同时举行。 | No clear semantic error identified |
| unknown-en | 请在周一之前不要打开B-17包裹。 | No clear semantic error identified |

## mt-quality-stq-seed2026 / sampling-context

[Raw results](mt-quality-stq-seed2026/results.json)

| Case | Output | Review |
|---|---|---|
| museum | Let’s visit the museum this afternoon, okay? | No clear semantic error identified |
| unrelated-number | Please reply by Friday. | NOTE: deadline weakened: before Friday becomes by Friday |
| negation | I didn’t send the file to Li Ming. | No clear semantic error identified |
| time-negation | 会议将于下午3点开始，而不是4点。 | No clear semantic error identified |
| name-number | 请在周五之前拨打138-0013-8000联系王医生。 | No clear semantic error identified |
| decimal-unit | The temperature rose from minus 5.5 degrees Celsius to 2 degrees Celsius. | No clear semantic error identified |
| unknown-source | 今天会下雨。 | FAIL: aspect/tense shift: ongoing rain becomes future rain |
| japanese-source | 今天下着雨。 | No clear semantic error identified |
| language-codes | Please close the door. | No clear semantic error identified |
| proper-name | 去苏黎世的火车于06:45从12号站台出发。 | No clear semantic error identified |
| female-pronoun | 她明天会到。 | No clear semantic error identified |
| bank-sense | 他坐在河岸上。 | No clear semantic error identified |
| context-time | 会议将同时举行。 | No clear semantic error identified |
| unknown-en | 请务必在周一之前打开A-12包装。 | FAIL: negation reversal: prohibition becomes instruction to open before Monday; FAIL: identifier substitution from context: B-17 becomes A-12 |
