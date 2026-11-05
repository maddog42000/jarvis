import { describe, expect, it } from "vitest";
import { approximateSpeechOffset, speechTextStartOffset } from "./chatSpeechTransportUtils";

describe("approximateSpeechOffset", () => {
  it("estimates about five seconds as roughly 70 spoken characters", () => {
    expect(approximateSpeechOffset("x".repeat(200), 5)).toBe(70);
  });

  it("clamps negative time to the start and long time to the text length", () => {
    expect(approximateSpeechOffset("hello", -5)).toBe(0);
    expect(approximateSpeechOffset("hello", 50)).toBe(5);
  });

  it("counts the speech-cleaned text rather than markdown markup", () => {
    expect(approximateSpeechOffset("**hello**", 1)).toBe(5);
  });

  it("recognizes the active full reply and the expected resumed suffix", () => {
    expect(speechTextStartOffset("hello world", "hello world")).toBe(0);
    expect(speechTextStartOffset("world", "hello world", "world")).toBe(6);
    expect(speechTextStartOffset("other reply", "hello world", "world")).toBeNull();
  });
});
