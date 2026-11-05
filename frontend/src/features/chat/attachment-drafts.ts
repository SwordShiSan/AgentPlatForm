import type { components } from '../../shared/api/generated/http';

export type AttachmentReference = components['schemas']['AttachmentReference'];
/** Original request identities and display metadata only: never File, ArrayBuffer or document text. */
export interface AttachmentDraft {
  key: string;
  name: string;
  uploadRequest?: components['schemas']['CreateAttachmentUpload'];
  uploadId?: string;
  attachmentId?: string;
  fileId?: string;
  versionId?: string;
  extractionKey: string;
  extractionId?: string;
}
