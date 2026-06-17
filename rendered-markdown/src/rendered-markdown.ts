export type RenderedMarkdownVariant = "assistantMessage" | "outgoingMessage" | "standalone";

export interface RenderedMarkdownPayload {
  markdown: string;
  variant: RenderedMarkdownVariant;
  isStreaming: boolean;
  tokens: Record<string, string>;
  renderId?: string;
}

export const markdownSignalPattern =
  /(^#{1,6}\s)|(^\s*[-*+]\s+)|(^\s*\d+[.)]\s+)|(^>\s)|(^```)|(^~~~)|(^\s*\|.+\|\s*$)|(^\s{4,}\S)|(^\s*(-{3,}|\*{3,}|_{3,})\s*$)/m;

export function shouldUseRenderedMarkdown(markdown: string): boolean {
  return markdownSignalPattern.test(markdown);
}

// The checked-in native bundle implements the same contract without requiring a
// platform build step. This source package is the canonical place for the future
// markdown-it + Shiki + DOMPurify bundle.
