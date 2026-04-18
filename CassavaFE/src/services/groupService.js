// src/services/groupService.js
// Axios instance for /mongo/field-group endpoints (Field Group + Field Group Sensor)
import axios from 'axios';

const groupService = axios.create({
  baseURL: 'http://localhost:8081/mongo/field-group',
  headers: {
    'Content-Type': 'application/json',
  }
});

export default groupService;
