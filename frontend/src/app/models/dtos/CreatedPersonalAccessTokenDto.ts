import { PersonalAccessTokenDto } from './PersonalAccessTokenDto';

/** Creation response. `token` is shown exactly once and never retrievable again. */
export interface CreatedPersonalAccessTokenDto {
  token: string;
  metadata: PersonalAccessTokenDto;
}
