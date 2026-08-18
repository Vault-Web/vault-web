import { Component, OnInit } from '@angular/core';
import { UserService } from '../../services/user.service';
import { UserDto } from '../../models/dtos/UserDto';
import { CommonModule } from '@angular/common';
import { PrivateChatDialogComponent } from '../private-chat-dialog/private-chat-dialog.component';
import { PrivateChatService } from '../../services/private-chat.service';
import { PrivateChatDto } from '../../models/dtos/PrivateChatDto';
import { AuthService } from '../../services/auth.service';
import { ActivatedRoute, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { UiToastService } from '../../core/services/ui-toast.service';
import { GroupService } from '../../services/group.service';
import { GroupDto } from '../../models/dtos/GroupDto';

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    PrivateChatDialogComponent,
    ButtonModule,
    DialogModule,
    InputTextModule,
  ],
  templateUrl: './home.component.html',
  styleUrls: ['./home.component.scss'],
})
export class HomeComponent implements OnInit {
  private readonly USER_EXPANSION_SESSION_KEY = 'home:isAllUsersExpanded';

  users: UserDto[] = [];
  privateChats: PrivateChatDto[] = [];
  userGroups: GroupDto[] = [];

  filteredUsers: UserDto[] = [];
  filteredPrivateChats: PrivateChatDto[] = [];
  filteredUserGroups: GroupDto[] = [];

  isLoading = true;
  error: string | null = null;

  selectedUsername: string | null = null;
  privateChatId: number | null = null;
  selectedGroupName: string | null = null;
  selectedGroupId: number | null = null;

  searchText = '';
  isAllUsersExpanded = false;

  // Edit/Clear chats properties
  isEditMode = false;
  selectedChatIds: Set<number> = new Set();
  showClearConfirmDialog = false;
  isProcessing = false;

  // Create Group Properties
  showGroupDialog = false;
  newGroupName = '';
  groupDescription = '';

  currentUsername: string | null = null;
  private requestedPrivateChatId: number | null = null;
  private requestedGroupId: number | null = null;
  private requestedGroupName: string | null = null;

  constructor(
    private userService: UserService,
    private privateChatService: PrivateChatService,
    private authService: AuthService,
    private router: Router,
    private route: ActivatedRoute,
    private toast: UiToastService,
    private groupService: GroupService,
  ) {}

  ngOnInit(): void {
    this.currentUsername = this.authService.getUsername();
    this.route.queryParamMap.subscribe((params) => {
      const rawChatId = params.get('privateChatId');
      const parsedChatId = rawChatId ? Number(rawChatId) : NaN;
      this.requestedPrivateChatId =
        Number.isInteger(parsedChatId) && parsedChatId > 0
          ? parsedChatId
          : null;

      const rawGroupId = params.get('groupId');
      const parsedGroupId = rawGroupId ? Number(rawGroupId) : NaN;
      this.requestedGroupId =
        Number.isInteger(parsedGroupId) && parsedGroupId > 0
          ? parsedGroupId
          : null;
      this.requestedGroupName = params.get('groupName');

      this.tryOpenRequestedPrivateChat();
      this.tryOpenRequestedGroupChat();
    });

    if (!this.currentUsername) {
      this.router.navigate(['/login']);
      this.error = 'User not logged in.';
      this.isLoading = false;
      return;
    }
    this.loadData();
    const saved = sessionStorage.getItem(this.USER_EXPANSION_SESSION_KEY);
    this.isAllUsersExpanded = saved === 'true'; //default false
  }

  private loadData() {
    this.isLoading = true;
    this.error = null;
    forkJoin({
      users: this.userService.getAllUsers(),
      chats: this.privateChatService.getUserPrivateChats(),
      groups: this.groupService.getUserGroups(),
    }).subscribe({
      next: ({ users, chats, groups }) => {
        this.users = users || [];
        this.privateChats = chats || [];
        this.userGroups = groups || [];
        this.filteredUsers = this.filterUsers(this.searchText, this.users);
        this.filteredPrivateChats = this.filterPrivateChats(
          this.searchText,
          this.privateChats,
        );
        this.filteredUserGroups = this.filterGroups(
          this.searchText,
          this.userGroups,
        );
        this.isLoading = false;
        this.tryOpenRequestedPrivateChat();
        this.tryOpenRequestedGroupChat();
      },
      error: (err: unknown) => {
        this.error = 'Failed to Load data.';
        if (!this.isHttpStatusZero(err)) {
          this.toast.error(
            'Load failed',
            'Could not load chats, users, and groups.',
          );
        }
        this.isLoading = false;
      },
    });
  }

  openChat(username: string): void {
    if (!this.currentUsername) return;

    this.selectedUsername = username;

    this.privateChatService.getOrCreatePrivateChat(username).subscribe({
      next: (chat: PrivateChatDto) => {
        this.privateChatId = chat.id;
      },
      error: () => {
        console.error('Failed to get or create private chat');
        this.toast.error(
          'Chat unavailable',
          'Could not open this chat right now.',
        );
      },
    });
  }

  closeChat(): void {
    this.selectedUsername = null;
    this.privateChatId = null;
    this.selectedGroupId = null;
    this.selectedGroupName = null;
  }

  toggleEditMode() {
    this.isEditMode = !this.isEditMode;
    if (!this.isEditMode) {
      this.selectedChatIds.clear();
    }
  }

  toggleChatSelection(chatId: number) {
    if (this.selectedChatIds.has(chatId)) {
      this.selectedChatIds.delete(chatId);
    } else {
      this.selectedChatIds.add(chatId);
    }
  }

  isChatSelected(chatId: number): boolean {
    return this.selectedChatIds.has(chatId);
  }

  get hasSelectedChats(): boolean {
    return this.selectedChatIds.size > 0;
  }

  openPrivateChat(chat: PrivateChatDto) {
    if (this.isEditMode) return; //Don't open chat in edit mode
    if (!this.currentUsername) return;

    const otherUserName =
      chat.username1 === this.currentUsername ? chat.username2 : chat.username1;
    this.selectedUsername = otherUserName;
    this.privateChatId = chat.id;
  }

  openGroupChat(group: GroupDto) {
    if (this.isEditMode) return;
    this.selectedGroupName = group.name;
    this.selectedGroupId = group.id;
  }

  onGroupChatKeydown(event: KeyboardEvent, group: GroupDto) {
    if (event.key !== 'Enter' && event.key !== ' ') {
      return;
    }
    event.preventDefault();
    this.openGroupChat(group);
  }

  private tryOpenRequestedPrivateChat(): void {
    if (!this.requestedPrivateChatId || this.isLoading) {
      return;
    }

    const chatToOpen = this.privateChats.find(
      (chat) => chat.id === this.requestedPrivateChatId,
    );
    if (!chatToOpen) {
      return;
    }

    this.openPrivateChat(chatToOpen);
    this.requestedPrivateChatId = null;
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { privateChatId: null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  private tryOpenRequestedGroupChat(): void {
    if (!this.requestedGroupId || this.isLoading) {
      return;
    }

    const groupToOpen = this.userGroups.find(
      (g) => g.id === this.requestedGroupId,
    );
    if (!groupToOpen) {
      return;
    }

    this.openGroupChat(groupToOpen);
    this.requestedGroupId = null;
    this.requestedGroupName = null;
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { groupId: null, groupName: null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  getOtherUsername(chat: PrivateChatDto): string {
    if (!this.currentUsername) return '';

    return chat.username1 === this.currentUsername
      ? chat.username2
      : chat.username1;
  }

  openClearConfirmDialog() {
    if (this.hasSelectedChats) {
      this.showClearConfirmDialog = true;
    }
  }

  cancelDialog() {
    if (this.isProcessing) {
      return;
    }
    this.showClearConfirmDialog = false;
    this.showGroupDialog = false;
    this.newGroupName = '';
    this.groupDescription = '';
  }

  confirmClearChats() {
    this.isProcessing = true;
    const chatIds = Array.from(this.selectedChatIds);
    this.privateChatService.clearMultiplePrivateChats(chatIds).subscribe({
      next: (_response) => {
        this.showClearConfirmDialog = false;
        this.selectedChatIds.clear();
        this.isProcessing = false;
        this.isEditMode = false;
        this.toast.success('Chats cleared', 'Selected chats were removed.');
        //Reload data to reflect changes
        this.loadData();
      },
      error: (err) => {
        console.error('Failed to clear chats ', err);
        this.error = 'Failed to clear chats. Please try again.';
        this.isProcessing = false;
        this.toast.error('Clear failed', 'Could not clear selected chats.');
      },
    });
  }

  openCreateGroupDialog() {
    if (this.hasSelectedChats) {
      this.showGroupDialog = true;
      this.newGroupName = '';
    }
  }

  confirmCreateGroup() {
    if (!this.newGroupName.trim()) {
      return;
    }
    const groupName = this.newGroupName.trim();
    this.isProcessing = true;
    const chatIds = Array.from(this.selectedChatIds);
    this.privateChatService
      .createGroupFromChats(chatIds, this.newGroupName, this.groupDescription)
      .subscribe({
        next: () => {
          this.showGroupDialog = false;
          this.isProcessing = false;
          this.isEditMode = false;
          this.newGroupName = '';
          this.groupDescription = '';
          this.selectedChatIds.clear();
          this.toast.success(
            'Group created',
            `"${groupName}" created successfully.`,
          );
          this.loadData();
        },
        error: (_err) => {
          this.error = 'Failed to create group. Please Try again.';
          this.isProcessing = false;
          this.toast.error(
            'Create group failed',
            'Please try again in a moment.',
          );
        },
      });
  }

  onSearchChange() {
    this.filteredUsers = this.filterUsers(this.searchText, this.users);
    this.filteredPrivateChats = this.filterPrivateChats(
      this.searchText,
      this.privateChats,
    );
    this.filteredUserGroups = this.filterGroups(
      this.searchText,
      this.userGroups,
    );
  }

  private filterUsers(searchText: string, users: UserDto[]) {
    if (!searchText.trim()) return [...users];
    const term = searchText.trim();
    return users.filter((user) => this.matchUserName(user.username, term));
  }

  private filterPrivateChats(
    searchText: string,
    privateChats: PrivateChatDto[],
  ) {
    if (!searchText.trim()) return [...privateChats];
    const term = searchText.trim();
    return privateChats.filter((chat) =>
      this.matchUserName(this.getOtherUsername(chat), term),
    );
  }

  private filterGroups(searchText: string, groups: GroupDto[]) {
    if (!searchText.trim()) return [...groups];
    const term = searchText.trim().toLowerCase();
    return groups.filter((group) => group.name.toLowerCase().includes(term));
  }

  private matchUserName(userName: string, term: string): boolean {
    return userName.toLowerCase().includes(term.toLowerCase());
  }

  toggleUserExpansion() {
    this.isAllUsersExpanded = !this.isAllUsersExpanded;
    sessionStorage.setItem(
      this.USER_EXPANSION_SESSION_KEY,
      String(this.isAllUsersExpanded),
    );
  }

  displayUsername(username: string): string {
    return username === this.currentUsername ? `${username} (You)` : username;
  }

  getOtherUser(chat: PrivateChatDto): UserDto | undefined {
    const otherUserName = this.getOtherUsername(chat);
    return this.getUserByUsername(otherUserName);
  }

  getUserByUsername(username: string): UserDto | undefined {
    return this.users.find((u) => u.username === username);
  }

  getCurrentUser(): UserDto | undefined {
    if (!this.currentUsername) return undefined;
    return this.getUserByUsername(this.currentUsername);
  }

  getProfilePictureUrl(user: UserDto | undefined): string | null {
    if (!user || !user.profilePicture) return null;
    return this.userService.getProfilePictureUrl(user.profilePicture);
  }

  private isHttpStatusZero(err: unknown): boolean {
    const candidate = err as { status?: number };
    return candidate?.status === 0;
  }
}
