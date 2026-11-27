/** A scope that can be granted to a personal access token. */
export interface PatScopeDto {
  value: string;
  description: string;
  endpoints: string[];
}
