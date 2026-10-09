import axios from 'axios';
export const TOKEN_KEY='retailiq_token';
export const client=axios.create({baseURL:(import.meta.env.VITE_API_BASE_URL||'').replace(/\/$/, '')+'/api/v1',headers:{'Content-Type':'application/json'}});
client.interceptors.request.use(config=>{const token=localStorage.getItem(TOKEN_KEY);if(token)config.headers.Authorization=`Bearer ${token}`;if(!config.headers.get('X-Request-ID'))config.headers.set('X-Request-ID',window.crypto.randomUUID());return config});
client.interceptors.response.use(response=>response,error=>{if(error.response?.status===401&&localStorage.getItem(TOKEN_KEY)){localStorage.removeItem(TOKEN_KEY);window.dispatchEvent(new Event('retailiq:unauthorized'))}return Promise.reject(error)});
export function errorMessage(error:unknown){if(axios.isAxiosError(error)){const d=error.response?.data as {message?:string;detail?:string;error?:string}|undefined;return d?.message||d?.detail||d?.error||error.message||'The request could not be completed.'}return error instanceof Error?error.message:'Something went wrong.'}
