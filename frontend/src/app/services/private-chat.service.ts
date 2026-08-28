import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { PrivateChatDto } from '../models/dtos/PrivateChatDto';
import { environment } from '../../environments/environment';
import { ChatMessageDto } from '../models/dtos/ChatMessageDto';
import { DeviceDto } from '../models/dtos/DeviceDto';
import { BatchOperationResponse } from '../models/dtos/BatchOperationResponse';
import { CreateGroupFromChatsRequest } from '../models/dtos/CreateGroupFromChatsRequest';
import { ClearChatRequestDto } from '../models/dtos/ClearChatRequestDto';

@Injectable({
  providedIn: 'root',
})
export class PrivateChatService {
  private apiUrl = environment.mainApiUrl;

  constructor(private http: HttpClient) {}

  getOrCreatePrivateChat(username2: string): Observable<PrivateChatDto> {
    return this.http.get<PrivateChatDto>(
      `${this.apiUrl}/private-chats/between?receiver=${encodeURIComponent(username2)}`,
    );
  }

  getMessages(privateChatId: number): Observable<ChatMessageDto[]> {
    return this.http.get<ChatMessageDto[]>(
      `${this.apiUrl}/private-chats/private?privateChatId=${privateChatId}`,
    );
  }

  getDevices(privateChatId: number): Observable<DeviceDto[]> {
    const params = new HttpParams().set('privateChatId', String(privateChatId));
    return this.http.get<DeviceDto[]>(`${this.apiUrl}/private-chats/devices`, {
      params,
    });
  }

  getUserPrivateChats(): Observable<PrivateChatDto[]> {
    return this.http.get<PrivateChatDto[]>(
      `${this.apiUrl}/private-chats/user-chats`,
    );
  }

  clearMultiplePrivateChats(
    privateChatIds: number[],
  ): Observable<BatchOperationResponse> {
    const request: ClearChatRequestDto = {
      privateChatIds,
    };
    return this.http.post<BatchOperationResponse>(
      `${this.apiUrl}/private-chats/clear-multiple`,
      request,
    );
  }

  createGroupFromChats(
    privateChatIds: number[],
    groupName: string,
    description: string,
  ): Observable<BatchOperationResponse> {
    const request: CreateGroupFromChatsRequest = {
      privateChatIds,
      groupName,
      description,
    };
    return this.http.post<BatchOperationResponse>(
      `${this.apiUrl}/private-chats/create-group-from-chats`,
      request,
    );
  }
}
