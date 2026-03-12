# CSV Exam Format Guide

## Single Required CSV Format

Use this format only:

```csv
Difficulty,Question_Text,Choice_A,Choice_B,Choice_C,Correct_Answer
Easy,What does URL stand for?,Uniform Resource Locator,Universal Resource Link,,A
Easy,Define software in your own words.,,,,A set of instructions that tell hardware what to do.
```

## Column Rules

- `Difficulty`: `Easy`, `Medium`, or `Hard`.
- `Question_Text`: Question text.
- `Choice_A ... Choice_Z`: Choices for multiple-choice questions. You can add as many lettered choice columns as needed.
- `Correct_Answer`:
  - For multiple-choice: Use letter (`A`, `B`, `C`, ...) that matches your choice columns (`Choice_A`, `Choice_B`, ...).
  - For open-ended: Put the expected answer text.

## Open-Ended Detection

A row is treated as open-ended when all choice columns are blank.

A row is also treated as open-ended when `Correct_Answer` is blank.

When open-ended:

- the question is converted to text-input mode,
- choices are ignored,
- if `Correct_Answer` is blank, it defaults to `MANUAL_GRADE`.

## Notes

- Choice text should be plain (no `(A)` prefix needed).
- Keep header names exactly as shown to avoid import errors.
- Save file as UTF-8 CSV.
