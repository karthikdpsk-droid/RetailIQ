import axios, { type InternalAxiosRequestConfig } from 'axios';
export const TOKEN_KEY='retailiq_token';
export const client=axios.create({baseURL:(import.meta.env.VITE_API_BASE_URL||'').replace(/\/$/, '')+'/api/v1',headers:{'Content-Type':'application/json'}});
client.interceptors.request.use(config=>{const token=localStorage.getItem(TOKEN_KEY);if(token)config.headers.Authorization=`Bearer ${token}`;if(!config.headers.get('X-Request-ID'))config.headers.set('X-Request-ID',window.crypto.randomUUID());return config});
client.interceptors.response.use(response=>response,error=>{if(error.response?.status===401&&localStorage.getItem(TOKEN_KEY)){localStorage.removeItem(TOKEN_KEY);window.dispatchEvent(new Event('retailiq:unauthorized'))}return Promise.reject(error)});
const readRetryDelaysMs=[5_000,15_000,30_000,45_000,60_000,60_000] as const;
type RetryableRequestConfig=InternalAxiosRequestConfig & {_retailIqReadRetryCount?:number};
// Render Free services can take several minutes to wake; retry only safe reads.
client.interceptors.response.use(undefined,async(error:unknown)=>{
  if(!axios.isAxiosError(error)||!error.config)return Promise.reject(error);
  const config=error.config as RetryableRequestConfig;
  const method=config.method?.toLowerCase();
  const retryableMethod=method==='get'||method==='head'||method==='options';
  const retryableStatus=[502,503,504].includes(error.response?.status??0);
  if(!retryableMethod||!retryableStatus)return Promise.reject(error);
  const retryCount=config._retailIqReadRetryCount??0;
  const delay=readRetryDelaysMs[retryCount];
  if(delay===undefined){
    error.message='The API is still unavailable after automatic retries. Please try again shortly.';
    return Promise.reject(error);
  }
  config._retailIqReadRetryCount=retryCount+1;
  await new Promise<void>(resolve=>window.setTimeout(resolve,delay));
  return client.request(config);
});
export function errorMessage(error:unknown){if(axios.isAxiosError(error)){const d=error.response?.data as {message?:string;detail?:string;error?:string}|undefined;return d?.message||d?.detail||d?.error||error.message||'The request could not be completed.'}return error instanceof Error?error.message:'Something went wrong.'}
