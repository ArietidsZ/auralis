---
library_name: transformers
tags:
- translation
language:
- zh
- en
- fr
- pt
- es
- ja
- tr
- ru
- ar
- ko
- th
- it
- de
- vi
- ms
- id
- tl
- hi
- pl
- cs
- nl
- km
- my
- fa
- gu
- ur
- te
- mr
- he
- bn
- ta
- uk
- bo
- kk
- mn
- ug
---


<p align="center">
 <img src="https://github.com/Tencent-Hunyuan/HY-MT/raw/main/imgs/hunyuanlogo.png" width="400"/> <br>
</p><p></p>


<p align="center">
    🤗&nbsp;<a href="https://huggingface.co/collections/tencent/hy-mt15"><b>Hugging Face</b></a>&nbsp;&nbsp;|&nbsp;&nbsp;
    🕹️&nbsp;<a href="https://hunyuan.tencent.com/chat/HunyuanDefault?from=modelSquare&modelId=hunyuan-mt-1.8b"><b>Demo</b></a>&nbsp;&nbsp;&nbsp;&nbsp;
    🤖&nbsp;<a href="https://modelscope.cn/collections/Tencent-Hunyuan/HY-MT15"><b>ModelScope</b></a>&nbsp;&nbsp;|&nbsp;&nbsp;
</p>

<p align="center">
    🖥️&nbsp;<a href="https://hunyuan.tencent.com"><b>Official Website</b></a>&nbsp;&nbsp;|&nbsp;&nbsp;
    <a href="https://github.com/Tencent-Hunyuan/HY-MT"><b>Github</b></a>
</p>


## Model Introduction

Hunyuan Translation Model Version 1.5 includes a 1.8B translation model, HY-MT1.5-1.8B, and a 7B translation model, HY-MT1.5-7B. Both models focus on supporting mutual translation across 33 languages and incorporating 5 ethnic and dialect variations. Among them, HY-MT1.5-7B is an upgraded version of our WMT25 championship model, optimized for explanatory translation and mixed-language scenarios, with newly added support for terminology intervention, contextual translation, and formatted translation. Despite having less than one-third the parameters of HY-MT1.5-7B, HY-MT1.5-1.8B delivers translation performance comparable to its larger counterpart, achieving both high speed and high quality. After quantization, the 1.8B model can be deployed on edge devices and support real-time translation scenarios, making it widely applicable.

## Key Features and Advantages

- HY-MT1.5-1.8B achieves the industry-leading performance among models of the same size, surpassing most commercial translation APIs.
- HY-MT1.5-1.8B supports deployment on edge devices and real-time translation scenarios, offering broad applicability.
- HY-MT1.5-7B, compared to its September open-source version, has been optimized for annotated and mixed-language scenarios.
- Both models support terminology intervention, contextual translation, and formatted translation.

## Related News
* 2025.12.30, we have open-sourced **HY-MT1.5-1.8B** and **HY-MT1.5-7B** on Hugging Face.
* 2025.9.1, we have open-sourced  **Hunyuan-MT-7B** , **Hunyuan-MT-Chimera-7B** on Hugging Face.
<br>


## Performance

<div align='center'>
<img src="https://github.com/Tencent-Hunyuan/HY-MT/raw/main/imgs/overall_performance.png" width = "100%" />
</div>
You can refer to our technical report for more experimental results and analysis.

<a href="https://github.com/Tencent-Hunyuan/HY-MT/raw/main/HY_MT1_5_Technical_Report.pdf"><b>Technical Report</b> </a>

&nbsp;

## Model Links
| Model Name  | Description | Download |
| ----------- | ----------- |-----------
| HY-MT1.5-1.8B  | Hunyuan 1.8B translation model |🤗 [Model](https://huggingface.co/tencent/HY-MT1.5-1.8B)|
| HY-MT1.5-1.8B-FP8 | Hunyuan 1.8B translation model, fp8 quant    | 🤗 [Model](https://huggingface.co/tencent/HY-MT1.5-1.8B-FP8)|
| HY-MT1.5-1.8B-GPTQ-Int4 | Hunyuan 1.8B translation model, int4 quant    | 🤗 [Model](https://huggingface.co/tencent/HY-MT1.5-1.8B-GPTQ-Int4)|
| HY-MT1.5-7B | Hunyuan 7B translation model    | 🤗 [Model](https://huggingface.co/tencent/HY-MT1.5-7B)|
| HY-MT1.5-7B-FP8 | Hunyuan 7B translation model, fp8 quant     | 🤗 [Model](https://huggingface.co/tencent/HY-MT1.5-7B-FP8)|
| HY-MT1.5-7B-GPTQ-Int4 | Hunyuan 7B translation model, int4 quant     | 🤗 [Model](https://huggingface.co/tencent/HY-MT1.5-7B-GPTQ-Int4)|

## Prompts

### Prompt Template for ZH<=>XX Translation.
---
```
将以下文本翻译为{target_language}，注意只需要输出翻译后的结果，不要额外解释：

{source_text}
```
---

### Prompt Template for XX<=>XX Translation, excluding ZH<=>XX.
---
```
Translate the following segment into {target_language}, without additional explanation.

{source_text}
```
---

### Prompt Template for terminology intervention.
---
```
参考下面的翻译：
{source_term} 翻译成 {target_term}

将以下文本翻译为{target_language}，注意只需要输出翻译后的结果，不要额外解释：
{source_text}
```
---

### Prompt Template for contextual translation.
---
```
{context}
参考上面的信息，把下面的文本翻译成{target_language}，注意不需要翻译上文，也不要额外解释：
{source_text}

```
---

###  Prompt Template for formatted translation.
---
```
将以下<source></source>之间的文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释，原文中的<sn></sn>标签表示标签内文本包含格式信息，需要在译文中相应的位置尽量保留该标签。输出格式为：<target>str</target>

<source>{src_text_with_format}</source>
```
---

&nbsp;

### Use with llama.cpp
```SHELL
llama-cli -hf tencent/HY-MT1.5-1.8B-GGUF:Q8_0 -p "Translate the following segment into Chinese, without additional explanation.\n\nIt’s on the house." -n 4096 --temp 0.7 --top-k 20 --top-p 0.6 --repeat-penalty 1.05 --no-warmup
```

### Use with ollama
```SHELL
echo 'FROM hf.co/tencent/HY-MT1.5-1.8B-GGUF:Q8_0\nTEMPLATE """<｜hy_begin▁of▁sentence｜>{{ if .System }}{{ .System }}<｜hy_place▁holder▁no▁3｜>{{ end }}{{ if .Prompt }}<｜hy_User｜>{{ .Prompt }}{{ end }}<｜hy_Assistant｜>"""' > Modelfile
ollama create hy-mt1.5-1.8b -f Modelfile
ollama run hy-mt1.5-1.8b
```

We recommend using the following set of parameters for inference. Note that our model does not have the default system_prompt.

```json
{
  "top_k": 20,
  "top_p": 0.6,
  "repetition_penalty": 1.05,
  "temperature": 0.7
}
```

&nbsp;

Supported languages:
| Languages         | Abbr.   | Chinese Names   |
|-------------------|---------|-----------------|
| Chinese           | zh      | 中文            |
| English           | en      | 英语            |
| French            | fr      | 法语            |
| Portuguese        | pt      | 葡萄牙语        |
| Spanish           | es      | 西班牙语        |
| Japanese          | ja      | 日语            |
| Turkish           | tr      | 土耳其语        |
| Russian           | ru      | 俄语            |
| Arabic            | ar      | 阿拉伯语        |
| Korean            | ko      | 韩语            |
| Thai              | th      | 泰语            |
| Italian           | it      | 意大利语        |
| German            | de      | 德语            |
| Vietnamese        | vi      | 越南语          |
| Malay             | ms      | 马来语          |
| Indonesian        | id      | 印尼语          |
| Filipino          | tl      | 菲律宾语        |
| Hindi             | hi      | 印地语          |
| Traditional Chinese | zh-Hant| 繁体中文        |
| Polish            | pl      | 波兰语          |
| Czech             | cs      | 捷克语          |
| Dutch             | nl      | 荷兰语          |
| Khmer             | km      | 高棉语          |
| Burmese           | my      | 缅甸语          |
| Persian           | fa      | 波斯语          |
| Gujarati          | gu      | 古吉拉特语      |
| Urdu              | ur      | 乌尔都语        |
| Telugu            | te      | 泰卢固语        |
| Marathi           | mr      | 马拉地语        |
| Hebrew            | he      | 希伯来语        |
| Bengali           | bn      | 孟加拉语        |
| Tamil             | ta      | 泰米尔语        |
| Ukrainian         | uk      | 乌克兰语        |
| Tibetan           | bo      | 藏语            |
| Kazakh            | kk      | 哈萨克语        |
| Mongolian         | mn      | 蒙古语          |
| Uyghur            | ug      | 维吾尔语        |
| Cantonese         | yue     | 粤语            |
