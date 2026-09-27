const useHttps = false;
const protocol = useHttps ? 'https' : 'http';
const host = 'localhost';

export const environment = {
  production: false,
  mainHostAddress: `${protocol}://${host}:8080`,
  mainApiUrl: `${protocol}://${host}:8080/api`,
  cloudServiceApiUrl: `${protocol}://${host}:8090/api`,
};
