import { HttpInterceptorFn } from '@angular/common/http';
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = localStorage.getItem('lendix_auth');
  const expires = Number(localStorage.getItem('lendix_session_expires') || 0);
  if (auth && expires > Date.now()) { localStorage.setItem('lendix_session_expires', String(Date.now() + 30 * 60 * 1000)); return next(req.clone({setHeaders:{Authorization:`Basic ${auth}`}})); }
  localStorage.removeItem('lendix_auth'); localStorage.removeItem('lendix_logged_in'); localStorage.removeItem('lendix_session_expires');
  return next(req);
};
