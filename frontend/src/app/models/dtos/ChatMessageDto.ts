export interface ChatMessageDto {
  content?: string;
  cipherText?: string;
  iv?: string;
  senderUsername?: string;
  senderId?: number;
  groupId?: number | null;
  privateChatId?: number;
  timestamp: string;
}
