# CSV Exam Format Guide

## Single Required CSV Format

Use this format only:

```csv
Difficulty,Question_Text,Choice_1,Choice_2,Choice_3,Correct_Answer
Easy,What does URL stand for?,(A) Uniform Resource Locator,(B) Universal Resource Link,,A
Easy,Define software in your own words.,,,,A set of instructions that tell hardware what to do.
```

## Column Rules

- `Difficulty`: `Easy`, `Medium`, or `Hard`.
- `Question_Text`: Question text.
- `Choice_1 ... Choice_N`: Choices for multiple-choice questions. You can add more choice columns as needed.
- `Correct_Answer`:
  - For multiple-choice: Use letter (`A`, `B`, `C`, ...), or full answer text.
  - For open-ended: Put the expected answer text.

## Open-Ended Detection

A row is treated as open-ended when all choice columns are blank.

When open-ended:

- the question is converted to text-input mode,
- choices are ignored,
- if `Correct_Answer` is blank, it defaults to `MANUAL_GRADE`.

## Notes

- Choice labels like `(A)` or `A)` are automatically removed from choice text.
- Keep header names exactly as shown to avoid import errors.
- Save file as UTF-8 CSV.
