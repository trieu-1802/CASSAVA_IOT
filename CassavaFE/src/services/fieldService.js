// src/services/api.js
/*import axios from 'axios';

const api = axios.create({
  baseURL: 'http://localhost:8000/api/v1', // Khớp với cấu trúc BE của cậu
});

api.interceptors.request.use((config) => {
  const user = JSON.parse(localStorage.getItem('user'));
  if (user?.accessToken) {
    config.headers['token'] = `Bearer ${user.accessToken}`;
  }
  return config;
});
export default api;
*/
// src/services/api.js
import axios from 'axios';

const fieldService = axios.create({
  baseURL: 'http://localhost:8081/mongo', 
  headers: {
    'Content-Type': 'application/json',
  }
});


export default fieldService;