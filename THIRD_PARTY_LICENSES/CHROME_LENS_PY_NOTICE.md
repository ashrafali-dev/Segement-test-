# chrome-lens-py attribution

NovelStudio's Google Lens client remains its existing Kotlin implementation. The live-screen latency and image-preparation changes were informed by the public reference client:

- Project: https://github.com/bropines/chrome-lens-py
- License: MIT
- Adapted ideas: persistent HTTP connection reuse, Chromium-compatible image sizing/encoding, and transparent-image handling using meaningful-alpha detection and a contrasting background.
- No Python runtime or alternate OCR engine is added to NovelStudio.

The following is the project's MIT license notice.

MIT License

Copyright (c) 2024 Bropines

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
