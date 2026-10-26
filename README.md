<div align="center">

# Novel Studio — Reading & Translation Overlay

[English](README.md) · [বাংলা](README_bn.md)

</div>

---

## What is Novel Studio?

Novel Studio is an Android reading app that works as a middleman between the page you are reading and the AI chatbot you choose. For web novels, it can extract chapter text from the page and send it to the chatbot for translation. For image-based webtoons and manga, it can use OCR to recognize text in the images, send that text through the translation workflow, and place the translated result back over the original page. The idea is simple: keep reading on the page you already use while Novel Studio handles the steps between the source text and its translation.

Novel Studio uses a chatbot's web interface rather than shipping its own general-purpose translation model. It does not host or redistribute the website's original chapters. Some features can save translated text locally, such as the translation library.

## What it can do

### Web novels
- Extract chapter text from a reader page when the site's structure allows it.
- Send the extracted text to the configured chatbot and insert the translation back into the reading workflow.
- Support next/previous chapter navigation and optional automatic extraction/translation.
- Keep translated chapters in a local library, with options to view and export them as TXT.
- Use a glossary and editable prompts to help keep names and terminology consistent.

### Webtoons and manga
- Recognize text inside chapter images with OCR.
- Show translated text over the original lettering instead of requiring a separate translated-image reader.
- Offer chapter-wide scanning, real-time scanning while reading, and manual OCR for a selected area or missed text.
- Keep the original view available so readers can compare the source and translation.
- Use bubble/layout analysis to place translated lines near their source positions.

### Reading tools
- Read websites inside the app's WebView.
- Use reader and chatbot views as supported by the current interface.
- Keep bookmarks and browsing history.
- Adjust translation prompts and target-language settings.

The results and available controls depend on the website, the selected mode, and the configured chatbot. Compatibility is not guaranteed for every website.

## How it works

**For a web novel:** Novel Studio reads the chapter text available from the reader page, sends it to the selected chatbot, and then places or saves the translated result.

**For an image-based chapter:** Novel Studio reads the page images or visible screen area, runs OCR to identify text and its position, sends the extracted text through the translation workflow, and renders the translated text over the corresponding areas. OCR and translation are separate steps: recognizing the source text correctly does not automatically guarantee a perfect translation.

Novel Studio does not require a Novel Studio-specific AI API key for its web-chatbot workflow. You use the chatbot website configured in the app; that service's own sign-in requirements, access rules, and usage limits still apply.

## OCR languages and reader-reported results

The current code supports ML Kit OCR recognizers for **Latin, Chinese, Japanese, and Korean** scripts. Google ML Kit is the default OCR engine in the current branch. Google Lens OCR is also available as an optional engine. OCR script selection can depend on the selected setting and the page being read.

From my experience as an everyday reader, ordinary Korean text and ordinary text in Japanese manga often work at **around 90% or better**. This is a personal, informal estimate—not a measured benchmark, a controlled study, or a guarantee for every page. It describes regular text, not every kind of lettering. Small or blurry text, unusual fonts, stylized sound effects, complex image layouts, and other difficult cases can reduce results.

The remaining mistakes are not all the same kind of problem. Some begin with OCR missing or misreading the source text; translation quality can also depend on the selected chatbot, target language, prompt, and surrounding story context. A stronger prompt and better context can help the translation, but they cannot recover source text that OCR never read correctly.

## Performance and testing

On the developer's own low-budget Samsung SM-A136B phone with 3 GB RAM, some chapter translation jobs have taken roughly **50 seconds** in testing. One recorded Fast Scan test processed **64 panels in 52 seconds**. These are observations from specific tests, not a universal speed claim or a guarantee that every chapter will finish within that time.

Actual speed can vary with the phone, available memory, network, number and quality of images, website behavior, and chatbot response time.

**This project has not yet undergone large-scale testing** across a wide range of phones, websites, chapters, and reading conditions. Community testing is needed to find compatibility issues, OCR mistakes, layout bugs, and performance problems. Feedback and bug reports are welcome.

## Technical overview

- **Platform:** Android, Kotlin, minSdk 24 (Android 7.0 or later); Java/Kotlin target 17.
- **Page integration:** Android WebView with a JavaScript bridge.
- **OCR:** ML Kit recognizers for Latin, Chinese, Japanese, and Korean; optional Google Lens OCR.
- **Text placement:** OCR text blocks and their geometry, bubble/layout analysis, and an HTML/JavaScript overlay.
- **Optional image cleanup:** ONNX Runtime supports optional detection/inpainting components. The LaMa inpainting model is downloaded separately rather than bundled in the APK; whether it can be used depends on device resources and model availability.
- **Translation:** The chatbot web interface configured by the reader; translation behavior depends on that service and the prompt.

These are implementation details, not a claim that every optional model is installed or enabled on every device.

## Community testing

This project is still being improved, and broad real-world validation is not complete. If you try it, reports that include the phone model, website, mode, source language, and a short description of what went wrong are especially helpful. Please avoid sharing private account information or session details in bug reports.

## Credits

Built by Ashraf Ali 🇧🇩

Special thanks to the open-source community and everyone whose work helped make this project possible.

---

Novel Studio is intended as a reading aid. Please respect the terms of the websites you use and the authors who create the original work.
