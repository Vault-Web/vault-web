export interface ChatSticker {
  id: string;
  label: string;
  src: string;
}

export const CHAT_EMOJIS = [
  '😀',
  '😂',
  '😊',
  '😍',
  '😎',
  '🥳',
  '🤔',
  '👍',
  '🙏',
  '🔥',
  '✨',
  '💙',
  '🎉',
  '✅',
  '👀',
  '💡',
];

export const CHAT_STICKERS: ChatSticker[] = [
  {
    id: 'spark',
    label: 'Spark',
    src: '/stickers/spark.svg',
  },
  {
    id: 'heart',
    label: 'Heart',
    src: '/stickers/heart.svg',
  },
  {
    id: 'thumbs-up',
    label: 'Thumbs up',
    src: '/stickers/thumbs-up.svg',
  },
  {
    id: 'party',
    label: 'Party',
    src: '/stickers/party.svg',
  },
];

export function findChatSticker(stickerId: string): ChatSticker | undefined {
  return CHAT_STICKERS.find((sticker) => sticker.id === stickerId);
}
