export interface ChatMessageDto {
  content?: string;
  senderUsername?: string;
  groupId?: number | null;
  privateChatId?: number;
  timestamp: string;
  senderDeviceId?: string;
  e2eePayload?: string;
  clientMessageId?: string;
}
