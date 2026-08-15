import type { Page, TestInfo } from '@playwright/test';

export async function proofVideo(
  closedContextPage: Page,
  testInfo: TestInfo,
  meta: { journey: string; caption: string; acTag: 'happy' | 'edge' | 'key-error' },
): Promise<void> {
  // ext: proof-gallery generator contract — 'proof-video' attachment + 'proof-*' annotation names
  const videoPath = await closedContextPage.video()?.path();

  if (videoPath) {
    await testInfo.attach('proof-video', { path: videoPath, contentType: 'video/webm' });
  }

  testInfo.annotations.push(
    { type: 'proof-journey', description: meta.journey },
    { type: 'proof-ac-tag', description: meta.acTag },
    { type: 'proof-caption', description: meta.caption },
  );
}
