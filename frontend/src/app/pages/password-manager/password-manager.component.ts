import { CommonModule } from '@angular/common';
import { Component, NgZone, OnDestroy, OnInit } from '@angular/core';
import {
  AbstractControl,
  FormBuilder,
  FormGroup,
  ReactiveFormsModule,
  ValidationErrors,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { PasswordManagerService } from '../../services/password-manager.service';
import { PasswordEntryDto } from '../../models/dtos/PasswordEntryDto';
import { PasswordEntryCreateRequestDto } from '../../models/dtos/PasswordEntryCreateRequestDto';
import { PasswordManagerVaultService } from '../../services/password-manager-vault.service';
import { PasswordManagerUnlockService } from '../../services/password-manager-unlock.service';
import { UiToastService } from '../../core/services/ui-toast.service';
import { UserService } from '../../services/user.service';

const passwordMatchValidator: ValidatorFn = (
  control: AbstractControl,
): ValidationErrors | null => {
  const password = control.get('password');
  const confirmPassword = control.get('confirmPassword');

  if (password && confirmPassword && password.value !== confirmPassword.value) {
    return { passwordMismatch: true };
  }
  return null;
};

const IDLE_TIMEOUT_MS = 15 * 60 * 1000;
const UPPER = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ';
const LOWER = 'abcdefghijklmnopqrstuvwxyz';
const NUMBERS = '0123456789';
const SPECIAL = '!@#$%^&*()_+-=[]{}|;:,.<>?';
const AMBIGUOUS = new Set(['l', 'I', '1', 'O', '0']);

type Strength = 'weak' | 'medium' | 'strong';

@Component({
  selector: 'app-password-manager',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './password-manager.component.html',
  styleUrl: './password-manager.component.scss',
})
export class PasswordManagerComponent implements OnInit, OnDestroy {
  entries: PasswordEntryDto[] = [];
  isLoading = false;
  hasLoadError = false;

  isCreateOpen = false;
  isEditing = false;
  editingId: number | null = null;
  isSaving = false;
  hasSaveError = false;
  createForm: FormGroup;

  revealedPasswords = new Map<number, string>();
  revealLoadingIds = new Set<number>();

  vaultInitialized: boolean | null = null;
  isVaultStatusLoading = false;

  isUnlocked = false;

  unlockForm: FormGroup;
  setupForm: FormGroup;
  isUnlocking = false;
  isSettingUp = false;
  vaultGateError: string | null = null;

  createPasswordVisible = false;
  confirmPasswordVisible = false;
  showSetupMasterPassword = false;
  showUnlockMasterPassword = false;

  // --- Password generator state ---
  isGeneratorOpen = false;
  generatorLength = 16;
  generatorUseUpper = true;
  generatorUseLower = true;
  generatorUseNumbers = true;
  generatorUseSpecial = true;
  generatorReadableOnly = false;
  generatedPreview = '';
  generatorError: string | null = null;

  private idleTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly activityEvents = [
    'mousemove',
    'mousedown',
    'keydown',
    'click',
    'touchstart',
  ];
  private readonly activityHandler = (): void => this.resetIdleTimer();

  constructor(
    private fb: FormBuilder,
    private passwordManagerService: PasswordManagerService,
    private vaultService: PasswordManagerVaultService,
    private unlockService: PasswordManagerUnlockService,
    private toast: UiToastService,
    private userService: UserService,
    private zone: NgZone,
  ) {
    this.createForm = this.fb.group(
      {
        name: ['', [Validators.required, Validators.maxLength(100)]],
        username: ['', [Validators.required]],
        password: ['', [Validators.required]],
        confirmPassword: ['', [Validators.required]],
        url: [''],
        notes: ['', [Validators.maxLength(500)]],
        categoryId: [''],
      },
      { validators: passwordMatchValidator },
    );

    const masterPasswordValidators = [
      Validators.required,
      Validators.minLength(8),
      Validators.maxLength(1024),
    ];

    this.unlockForm = this.fb.group({
      masterPassword: ['', masterPasswordValidators],
    });

    this.setupForm = this.fb.group({
      masterPassword: ['', masterPasswordValidators],
    });
  }

  toggleGenerator(): void {
    this.isGeneratorOpen = !this.isGeneratorOpen;
    if (this.isGeneratorOpen && !this.generatedPreview) {
      this.generatePassword();
    }
  }

  onGeneratorOptionChange(): void {
    this.generatePassword();
  }

  onGeneratorLengthChange(event: Event): void {
    const value = Number((event.target as HTMLInputElement).value);
    this.generatorLength = value;
    this.generatePassword();
  }

  private buildGeneratorPool(): string {
    let pool = '';
    if (this.generatorUseUpper) pool += UPPER;
    if (this.generatorUseLower) pool += LOWER;
    if (this.generatorUseNumbers) pool += NUMBERS;
    if (this.generatorUseSpecial) pool += SPECIAL;
    if (this.generatorReadableOnly) {
      pool = Array.from(pool)
        .filter((c) => !AMBIGUOUS.has(c))
        .join('');
    }
    return pool;
  }

  generatePassword(): void {
    const pool = this.buildGeneratorPool();
    if (!pool) {
      this.generatorError = 'Select at least one character type.';
      this.generatedPreview = '';
      return;
    }
    this.generatorError = null;

    const maxValid = Math.floor(256 / pool.length) * pool.length;
    const bytes = new Uint8Array(this.generatorLength * 2);
    crypto.getRandomValues(bytes);

    let result = '';
    let i = 0;
    while (result.length < this.generatorLength) {
      if (i >= bytes.length) {
        crypto.getRandomValues(bytes);
        i = 0;
      }
      const byte = bytes[i++];
      if (byte < maxValid) {
        result += pool[byte % pool.length];
      }
    }
    this.generatedPreview = result;
  }

  get generatorStrength(): Strength {
    const variety =
      Number(this.generatorUseUpper) +
      Number(this.generatorUseLower) +
      Number(this.generatorUseNumbers) +
      Number(this.generatorUseSpecial);
    const poolSize = this.buildGeneratorPool().length || 1;
    const bits = this.generatorLength * Math.log2(poolSize);
    if (bits < 40 || variety < 2) return 'weak';
    if (bits < 70) return 'medium';
    return 'strong';
  }

  useGeneratedPassword(): void {
    if (!this.generatedPreview || this.generatorError) {
      return;
    }
    this.createForm.patchValue({
      password: this.generatedPreview,
      confirmPassword: this.generatedPreview,
    });
    this.createPasswordVisible = true;
    this.confirmPasswordVisible = true;
    this.isGeneratorOpen = false;
  }

  private resetGenerator(): void {
    this.isGeneratorOpen = false;
    this.generatedPreview = '';
    this.generatorError = null;
  }

  ngOnInit(): void {
    this.updateUnlockState();
    this.registerActivityListeners();
    this.refreshVaultStatus();
    this.startIdleTimerIfNeeded();
  }

  ngOnDestroy(): void {
    this.clearIdleTimer();
    this.activityEvents.forEach((eventName) => {
      document.removeEventListener(eventName, this.activityHandler);
    });
  }

  private updateUnlockState(): void {
    this.isUnlocked = this.unlockService.isUnlocked();
  }

  isVaultUnlocked(): boolean {
    return this.isUnlocked;
  }

  addPassword(): void {
    if (!this.isVaultUnlocked()) {
      this.vaultGateError = 'Unlock your vault to manage passwords.';
      return;
    }

    this.hasSaveError = false;
    this.isEditing = false;
    this.editingId = null;

    this.createPasswordVisible = false;
    this.confirmPasswordVisible = false;
    this.resetGenerator();

    this.createForm.reset();
    this.isCreateOpen = true;
  }

  editPassword(entry: PasswordEntryDto): void {
    if (!this.isVaultUnlocked()) {
      this.vaultGateError = 'Unlock your vault to edit passwords.';
      return;
    }

    this.hasSaveError = false;
    this.isEditing = true;
    this.editingId = entry.id;
    this.createPasswordVisible = false;
    this.confirmPasswordVisible = false;
    this.resetGenerator();

    this.createForm.reset({
      name: entry.name ?? '',
      username: entry.username ?? '',
      password: '',
      url: entry.url ?? '',
      notes: entry.notes ?? '',
      categoryId: entry.categoryId ?? '',
    });
    this.isCreateOpen = true;
  }

  closeCreateModal(): void {
    if (this.isSaving) {
      return;
    }
    this.isCreateOpen = false;
    this.resetGenerator();
  }

  submitCreate(): void {
    this.hasSaveError = false;

    if (this.createForm.invalid) {
      this.createForm.markAllAsTouched();
      this.toast.warn(
        'Form incomplete',
        'Please fill all required fields and fix validation errors.',
      );
      return;
    }

    const raw = this.createForm.value;
    const categoryId =
      raw.categoryId === null ||
      raw.categoryId === undefined ||
      raw.categoryId === ''
        ? null
        : Number(raw.categoryId);

    const payload: PasswordEntryCreateRequestDto = {
      name: String(raw.name ?? '').trim(),
      username: String(raw.username ?? '').trim(),
      password: String(raw.password ?? ''),
      url: raw.url ? String(raw.url).trim() : null,
      notes: raw.notes ? String(raw.notes).trim() : null,
      categoryId: Number.isFinite(categoryId as number)
        ? (categoryId as number)
        : null,
    };

    this.isSaving = true;

    const req$ =
      this.isEditing && this.editingId !== null
        ? this.passwordManagerService.update(this.editingId, payload)
        : this.passwordManagerService.create(payload);

    req$.subscribe({
      next: () => {
        this.isSaving = false;
        this.isCreateOpen = false;
        this.toast.success(
          this.isEditing ? 'Entry updated' : 'Entry created',
          this.isEditing
            ? 'Password entry was updated.'
            : 'Password entry was created.',
        );
        this.loadEntries();
      },
      error: (err) => {
        this.isSaving = false;
        this.hasSaveError = true;
        console.error(
          this.isEditing
            ? 'Failed to update password entry'
            : 'Failed to create password entry',
          err,
        );
        this.toast.error(
          this.isEditing ? 'Update failed' : 'Create failed',
          'Could not save password entry.',
        );
      },
    });
  }

  deletePassword(entry: PasswordEntryDto): void {
    if (!this.isVaultUnlocked()) {
      this.vaultGateError = 'Unlock your vault to delete passwords.';
      return;
    }

    const confirmed = window.confirm(
      `Delete password entry "${entry.name}"? This cannot be undone.`,
    );
    if (!confirmed) {
      return;
    }

    this.passwordManagerService.delete(entry.id).subscribe({
      next: () => {
        this.entries = this.entries.filter((e) => e.id !== entry.id);
        this.revealedPasswords.delete(entry.id);
        this.revealLoadingIds.delete(entry.id);
        this.toast.success('Entry deleted', `"${entry.name}" was removed.`);
      },
      error: (err) => {
        this.handleApiError(err);
        console.error('Failed to delete password entry', err);
        this.toast.error('Delete failed', 'Could not delete password entry.');
      },
    });
  }

  retry(): void {
    this.loadEntries();
  }

  trackById(_: number, item: PasswordEntryDto): number {
    return item?.id ?? 0;
  }

  isRevealed(entryId: number): boolean {
    return this.revealedPasswords.has(entryId);
  }

  getRevealed(entryId: number): string {
    return this.revealedPasswords.get(entryId) ?? '';
  }

  toggleReveal(entryId: number): void {
    if (!this.isVaultUnlocked()) {
      this.vaultGateError = 'Unlock your vault to reveal passwords.';
      return;
    }

    if (this.isRevealed(entryId)) {
      this.revealedPasswords.delete(entryId);
      return;
    }

    if (this.revealLoadingIds.has(entryId)) {
      return;
    }

    this.revealLoadingIds.add(entryId);
    this.passwordManagerService.reveal(entryId).subscribe({
      next: (res) => {
        this.revealLoadingIds.delete(entryId);
        this.revealedPasswords.set(entryId, res.password);
        this.toast.info(
          'Password revealed',
          'Visible until you hide it again.',
        );
      },
      error: (err) => {
        this.revealLoadingIds.delete(entryId);
        this.handleApiError(err);
        console.error('Failed to reveal password', err);
        this.toast.error('Reveal failed', 'Could not reveal this password.');
      },
    });
  }

  submitUnlock(): void {
    this.vaultGateError = null;
    if (this.unlockForm.invalid) {
      this.unlockForm.markAllAsTouched();
      const message =
        this.getMasterPasswordError(
          this.unlockForm.get('masterPassword'),
          'Unlock',
        ) ?? 'Enter your master password.';
      this.vaultGateError = message;
      this.toast.warn('Invalid master password', message);
      return;
    }

    const masterPassword = String(this.unlockForm.value.masterPassword ?? '');
    this.isUnlocking = true;

    this.vaultService.unlock(masterPassword).subscribe({
      next: (res) => {
        this.isUnlocking = false;
        this.unlockService.setToken(res.token, res.expiresAt);
        this.updateUnlockState();
        this.unlockForm.reset();
        this.resetIdleTimer();
        this.toast.success(
          'Vault unlocked',
          'Password manager is now available.',
        );
        this.userService.logSecurityEvent('VAULT_UNLOCKED').subscribe({
          error: (err) =>
            console.error('Failed to log vault unlock event', err),
        });
        this.loadEntries();
      },
      error: (err) => {
        this.isUnlocking = false;
        this.handleApiError(err);
        this.toast.error(
          'Unlock failed',
          this.vaultGateError ?? 'Unlock failed.',
        );
      },
    });
  }

  submitSetup(): void {
    this.vaultGateError = null;
    if (this.setupForm.invalid) {
      this.setupForm.markAllAsTouched();
      const message =
        this.getMasterPasswordError(this.setupForm.get('masterPassword')) ??
        'Choose a valid master password.';
      this.vaultGateError = message;
      this.toast.warn('Invalid master password', message);
      return;
    }

    const masterPassword = String(this.setupForm.value.masterPassword ?? '');
    this.isSettingUp = true;

    this.vaultService.setup(masterPassword).subscribe({
      next: () => {
        this.isSettingUp = false;
        this.vaultInitialized = true;
        this.toast.success(
          'Vault initialized',
          'Now unlocking with your master password.',
        );

        this.unlockForm.setValue({ masterPassword });
        this.submitUnlock();

        this.setupForm.reset();
      },
      error: (err) => {
        this.isSettingUp = false;
        this.handleApiError(err);
        this.toast.error(
          'Setup failed',
          this.vaultGateError ?? 'Vault setup failed.',
        );
      },
    });
  }

  lockVault(autoLocked = false): void {
    this.clearIdleTimer();
    const token = this.unlockService.getToken();
    this.vaultService.lock(token).subscribe({
      next: () => {
        this.clearVaultState();
        this.vaultGateError = null;
        this.toast.info(
          'Vault locked',
          autoLocked
            ? 'Vault was locked after a period of inactivity.'
            : 'Sensitive data was hidden.',
        );
        this.userService.logSecurityEvent('VAULT_LOCKED').subscribe({
          error: (err) => console.error('Failed to log vault lock event', err),
        });
      },
      error: () => {
        this.clearVaultState();
      },
    });
  }

  private clearVaultState(): void {
    this.unlockService.clear();
    this.updateUnlockState();
    this.revealedPasswords.clear();
    this.revealLoadingIds.clear();
    this.entries = [];
  }

  private registerActivityListeners(): void {
    this.zone.runOutsideAngular(() => {
      this.activityEvents.forEach((eventName) => {
        document.addEventListener(eventName, this.activityHandler, {
          passive: true,
        });
      });
    });
  }

  private resetIdleTimer(): void {
    if (!this.isUnlocked) {
      this.clearIdleTimer();
      return;
    }

    this.clearIdleTimer();
    this.idleTimer = setTimeout(() => {
      this.zone.run(() => this.lockVault(true));
    }, IDLE_TIMEOUT_MS);
  }

  private startIdleTimerIfNeeded(): void {
    if (this.isUnlocked) {
      this.resetIdleTimer();
    }
  }

  private clearIdleTimer(): void {
    if (this.idleTimer !== null) {
      clearTimeout(this.idleTimer);
      this.idleTimer = null;
    }
  }

  private refreshVaultStatus(): void {
    this.isVaultStatusLoading = true;
    this.vaultGateError = null;

    this.vaultService.status().subscribe({
      next: (res) => {
        this.isVaultStatusLoading = false;
        this.vaultInitialized = !!res.initialized;

        this.updateUnlockState();
        if (this.vaultInitialized && this.isUnlocked) {
          this.startIdleTimerIfNeeded();
          this.loadEntries();
        }
      },
      error: (err) => {
        this.isVaultStatusLoading = false;
        this.vaultGateError = 'Failed to check vault status.';
        console.error('Failed to load vault status', err);
        this.toast.error(
          'Vault status failed',
          'Could not check vault status.',
        );
      },
    });
  }

  private handleApiError(err: unknown): void {
    const httpErr = err as HttpErrorResponse;
    if (!httpErr || typeof httpErr.status !== 'number') {
      this.vaultGateError = 'Request failed.';
      return;
    }

    if (httpErr.status === 409) {
      this.clearIdleTimer();
      this.clearVaultState();
      this.vaultInitialized = false;
      this.vaultGateError = 'Vault is not initialized yet.';
      return;
    }

    if (httpErr.status === 428) {
      this.clearIdleTimer();
      this.clearVaultState();
      this.vaultGateError =
        'Vault is locked. Please unlock with your master password.';
      return;
    }

    if (httpErr.status === 401) {
      this.vaultGateError = 'You are not logged in.';
      return;
    }

    this.vaultGateError = 'Request failed.';
  }

  private loadEntries(): void {
    if (!this.isVaultUnlocked()) {
      this.hasLoadError = false;
      this.isLoading = false;
      return;
    }

    this.isLoading = true;
    this.hasLoadError = false;

    this.passwordManagerService.getAll().subscribe({
      next: (data) => {
        this.entries = data;
        this.isLoading = false;
      },
      error: (err) => {
        this.isLoading = false;
        this.hasLoadError = true;
        this.handleApiError(err);
        console.error('Failed to load password entries', err);
        this.toast.error('Load failed', 'Could not load password entries.');
      },
    });
  }

  getSetupMasterPasswordError(): string | null {
    return this.getMasterPasswordError(this.setupForm.get('masterPassword'));
  }

  getUnlockMasterPasswordError(): string | null {
    return this.getMasterPasswordError(
      this.unlockForm.get('masterPassword'),
      'Unlock',
    );
  }

  private getMasterPasswordError(
    control: AbstractControl | null,
    mode: 'Setup' | 'Unlock' = 'Setup',
  ): string | null {
    if (!control || (!control.touched && !control.dirty)) {
      return null;
    }

    if (control.hasError('required')) {
      return mode === 'Unlock'
        ? 'Enter your master password.'
        : 'Master password is required.';
    }

    if (control.hasError('minlength')) {
      return 'Master password must be at least 8 characters.';
    }

    if (control.hasError('maxlength')) {
      return 'Master password is too long.';
    }

    return null;
  }
}
